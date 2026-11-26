"""Read-only public football sources, bounded requests and explicit missing coverage.

FotMob's public website data is an optional, undocumented source: fail closed on
schema/access changes. No proxy, credentials, paid API, or access-control bypass.
"""
import json
import threading
import time
import urllib.request
import zlib
from datetime import datetime, timezone, timedelta
from pathlib import Path

from app.sport_model import State, VERSION, goal_rate, parse_csv, probabilities, team_key

COMPETITIONS = {
    'soccer_france_ligue_one': (53, 'F1'), 'soccer_epl': (47, 'E0'),
    'soccer_germany_bundesliga': (54, 'D1'), 'soccer_spain_la_liga': (87, 'SP1'),
    'soccer_uefa_champs_league': (42, None), 'soccer_uefa_europa_league': (73, None)}
BASE = 'https://www.fotmob.com/api/data/'
_cache = {}
_lock = threading.RLock()
_last_call = 0.


def utc(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00')).astimezone(timezone.utc)


def fetch(url, ttl=1800, json_data=True):
    """Cache successful responses, short negative cache; never serve expired data."""
    global _last_call
    with _lock:
        now = time.time()
        if url in _cache and now < _cache[url][0]:
            cached, observed = _cache[url][1:]
            if cached is None: raise ValueError('Source temporairement indisponible')
            raw=zlib.decompress(cached)
            value=json.loads(raw) if json_data else raw.decode('utf-8-sig')
            return value, observed
        time.sleep(max(0, .15-(time.monotonic()-_last_call)))
        _last_call = time.monotonic()
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'AtlasBet/2.0 personal football analysis', 'Accept': 'application/json,text/csv'})
            with urllib.request.urlopen(req, timeout=12) as response:
                raw = response.read(8_000_001)
            if len(raw)>8_000_000: raise ValueError('Réponse trop volumineuse')
            value = json.loads(raw) if json_data else raw.decode('utf-8-sig')
            observed = datetime.now(timezone.utc).isoformat()
            _cache[url]=(now+ttl, zlib.compress(raw), observed)
            # Compressed byte cache: 32 MiB hard ceiling in the 256 MiB container.
            for k in list(_cache):
                if _cache[k][0]<now: del _cache[k]
            while len(_cache)>512 or sum(len(v[1] or b'') for v in _cache.values())>32*1024*1024:
                del _cache[next(iter(_cache))]
            return value, observed
        except Exception as error:
            _cache[url]=(now+300, None, None)
            raise ValueError('Source temporairement indisponible') from error


def load_state(now):
    year=now.year if now.month>=7 else now.year-1
    all_rows=[]; sources=[]; failures=[]
    for league in ['F1','E0','D1','SP1','I1','N1','P1','B1']:
        for y in [year-1, year]:
            season=f'{y%100:02d}{(y+1)%100:02d}'
            url=f'https://www.football-data.co.uk/mmz4281/{season}/{league}.csv'
            try:
                raw, observed=fetch(url, 21600, False)
                all_rows.extend(parse_csv(raw, league)); sources.append({'url':url,'observed_at':observed})
            except ValueError: failures.append(league+':'+season)
    state=State()
    for row in sorted(all_rows, key=lambda r:(r['date'],r['league'],r['home'])):
        if row['date']<now.date().isoformat(): state.update(row)
    return state,sources,failures


def find_match(competition, home, away, starts):
    league=COMPETITIONS[competition][0]
    data, observed=fetch(BASE+'matches?date='+starts.strftime('%Y%m%d'), 900)
    matches=[]
    for group in data.get('leagues', []):
        if group.get('primaryId', group.get('id'))!=league: continue
        for match in group.get('matches', []):
            if team_key(match['home']['name'])!=team_key(home) or team_key(match['away']['name'])!=team_key(away): continue
            if abs((utc(match['status']['utcTime'])-starts).total_seconds())<=900: matches.append(match)
    if len(matches)!=1: raise ValueError('Identité ou horaire du match non rapproché de façon unique')
    return matches[0], observed


def context(match, starts, now):
    url=BASE+'matchDetails?matchId='+str(match['id'])
    detail, observed=fetch(url, 300 if starts-now<timedelta(hours=3) else 1800)
    general=detail.get('general', {})
    if general.get('started') or general.get('finished') or utc(observed)>=starts:
        raise ValueError('Match commencé : aucune analyse rétrospective présentée comme prévision')
    if str(general.get('matchId'))!=str(match['id']) or abs((utc(general['matchTimeUTCDate'])-starts).total_seconds())>900:
        raise ValueError('Identité ou horaire contradictoire')
    lineup=detail.get('content',{}).get('lineup') or {}
    confirmed=lineup.get('lineupType')=='standard' and all(
        len((lineup.get(side+'Team') or {}).get('starters',[]))==11 and
        len({p.get('id') for p in (lineup.get(side+'Team') or {}).get('starters',[]) if p.get('id') is not None})==11
        for side in ['home','away'])
    result={'source':'FotMob', 'url':'https://www.fotmob.com/match/'+str(match['id']), 'observed_at':observed,
            'lineup_status':'CONFIRMED' if confirmed else 'PREDICTED' if lineup.get('lineupType')=='predicted' else 'UNKNOWN'}
    for side in ['home','away']:
        lt=lineup.get(side+'Team') or {}
        team_id=match[side]['id']
        if lt and lt.get('id')!=team_id: raise ValueError('Équipe de composition contradictoire')
        starters=[{'id':p['id'],'name':p['name'],'position':p.get('usualPlayingPositionId')} for p in lt.get('starters',[])]
        absent=[{'id':p['id'],'name':p['name'], 'type':(p.get('unavailability') or {}).get('type','unknown'),
                 'return':(p.get('unavailability') or {}).get('expectedReturn'),
                 'uncertain':(p.get('unavailability') or {}).get('expectedReturn')=='Doubtful'} for p in lt.get('unavailable',[])]
        result[side]={'starters':starters,'formation':lt.get('formation'),'absent':absent,
                      'absence_coverage':'REPORTED_NOT_EXHAUSTIVE' if 'unavailable' in lt else 'UNKNOWN',
                      'attack_factor':1.,'defence_factor':1.,'rotation':None,'impact_status':'UNAVAILABLE'}
        # Estimate changes against the previous three actual starting elevens. This
        # avoids deducting every long-term injury again from already weakened form.
        try:
            squad, squad_at=fetch(BASE+'teams?id='+str(team_id), 21600)
            if squad.get('details',{}).get('id')!=team_id: raise ValueError('Effectif contradictoire')
            players={p['id']:p for group in (squad.get('squad') or {}).get('squad',[]) for p in group.get('members',[]) if group.get('title')!='coach'}
            result[side]['squad_observed_at']=squad_at
            result[side]['squad_url']='https://www.fotmob.com/teams/'+str(team_id)+'/squad'
            if 'unavailable' not in lt and players:
                absent=[{'id':p['id'],'name':p['name'],'type':'injury',
                         'return':(p.get('injury') or {}).get('expectedReturn'),
                         'uncertain':(p.get('injury') or {}).get('expectedReturn')=='Doubtful'}
                        for p in players.values() if p.get('injured') or p.get('injury')]
                result[side]['absent']=absent
                result[side]['absence_coverage']='INJURIES_ONLY_NOT_EXHAUSTIVE'
            fixtures=[f for f in squad.get('fixtures',{}).get('allFixtures',{}).get('fixtures',[])
                      if f.get('status',{}).get('finished') and not f['status'].get('cancelled')
                      and f.get('tournament',{}).get('leagueId')!=489 and utc(f['status']['utcTime'])<now
                      and (now-utc(f['status']['utcTime'])).days<60]
            fixtures.sort(key=lambda f:f['status']['utcTime'], reverse=True)
            if fixtures:
                result[side]['rest_all_competitions']=(starts-utc(fixtures[0]['status']['utcTime'])).total_seconds()/86400
            previous=[]
            for f in fixtures[:3]:
                historic,_=fetch(BASE+'matchDetails?matchId='+str(f['id']),86400)
                old=historic.get('content',{}).get('lineup') or {}
                if old.get('lineupType')!='standard': continue
                for old_side in ['homeTeam','awayTeam']:
                    ot=old.get(old_side) or {}
                    if ot.get('id')==team_id and len(ot.get('starters',[]))==11:
                        previous.append({p['id'] for p in ot['starters']})
            if len(starters)==11 and previous and all(p['id'] in players for p in starters):
                current={p['id'] for p in starters}
                total=sum(float(p.get('goals') or 0)+float(p.get('assists') or 0) for p in players.values())
                production=lambda ids: sum(float(players.get(i,{}).get('goals') or 0)+float(players.get(i,{}).get('assists') or 0) for i in ids)
                change=(production(current)-sum(production(ids) for ids in previous)/len(previous))/max(10,total)
                # Deliberately bounded, explicitly heuristic, NOT a fitted causal effect.
                result[side].update(attack_factor=round(1+max(-.15,min(.15,.5*change)),4),
                                    rotation=round(sum(len(ids-current) for ids in previous)/len(previous),1),
                                    impact_status='HEURISTIC', reference_lineups=len(previous), squad_observed_at=squad_at,
                                    lineup_production=production(current), reference_production=sum(production(ids) for ids in previous)/len(previous),
                                    squad_production=total, reference_match_ids=[f['id'] for f in fixtures[:3]])
                for p in absent:
                    p['recent_starts']=sum(p['id'] in ids for ids in previous)
                    pp=players.get(p['id'],{})
                    p['goals']=pp.get('goals');p['assists']=pp.get('assists')
        except (ValueError, KeyError, TypeError):
            result[side]['impact_status']='UNAVAILABLE'
    return result


def analyze(request, now=None):
    now=now or datetime.now(timezone.utc)
    starts=utc(request['startsAt']); day=starts.date().isoformat()
    result={'model':VERSION,'model_status':'UNVALIDATED','observed_at':now.isoformat(),
            'starts_at':starts.isoformat(),'status':'INSUFFICIENT_DATA','decision':'ABSTAIN','stake':0,
            'probabilities':{},'reasons':[], 'sources':[], 'context':None}
    if starts<=now: result['reasons'].append('Match commencé : prévision interdite'); return result
    comp=request['competition']
    if comp not in COMPETITIONS: result['reasons'].append('Compétition non couverte'); return result
    state,sources,failures=load_state(now)
    result['sources']=sources;result['unavailable_feeds']=failures
    h,a=team_key(request['home']),team_key(request['away'])
    hx,ax,hp,ap=state.features(h,a,day)
    result['stats']={'home':hp,'away':ap}
    try:
        match,_=find_match(comp,request['home'],request['away'],starts)
        result['context']=context(match, starts, now)
    except (ValueError, KeyError, TypeError) as error:
        result['reasons'].append(str(error) if isinstance(error,ValueError) else 'Format de la source contexte indisponible')
    ctx=result['context']
    if min(hp['matches'],ap['matches'])<8 or any(p['last_match'] is None or (now.date()-datetime.fromisoformat(p['last_match']).date()).days>45 for p in [hp,ap]):
        result['reasons'].append('Au moins huit matchs récents par équipe nécessaires ; historique manquant ou périmé')
        return result
    if min(hp['shots_matches'],ap['shots_matches'])<6:
        result['reasons'].append('Couverture des tirs cadrés insuffisante'); return result
    artifact=json.loads(Path(__file__).with_name('sport_weights.json').read_text(encoding='utf-8'))
    if artifact['trained_through']>=day: raise ValueError('Modèle entraîné après la date de prévision')
    hr,ar=goal_rate(artifact['weights'],hx),goal_rate(artifact['weights'],ax)
    result['base_probabilities']=probabilities(hr,ar)
    # Rest measured over domestic history is the fitted feature. Cross-competition
    # rest and rotation are disclosed in context, never silently substituted.
    if ctx:
        hr*=ctx['home']['attack_factor']; ar*=ctx['away']['attack_factor']
    result.update(status='ANALYZED', probabilities=probabilities(hr,ar), expected_goals={'home':hr,'away':ar},
                  validation=artifact['validation'], features={'home':hx,'away':ax}, trained_through=artifact['trained_through'])
    result['lean']=max(result['probabilities'],key=result['probabilities'].get)
    result['fair_odds']={k:round(1/v,2) for k,v in result['probabilities'].items()}
    result['reasons'].append('Modèle expérimental : rentabilité et incertitude non validées')
    if COMPETITIONS[comp][1] is None:
        result['reasons'].append('Coupe européenne : transfert entre championnats non calibré ; prudence renforcée')
    if not ctx or ctx['lineup_status']!='CONFIRMED': result['reasons'].append('Composition officielle attendue')
    if ctx and any(ctx[s]['impact_status']=='UNAVAILABLE' for s in ['home','away']):
        result['reasons'].append('Effet des changements de joueurs non quantifiable sur une ou deux équipes')
    result['reasons'].append('Blessures et défense : pas de coefficient causal validé ; listes non exhaustives')
    result['explanation']=[
        f"Forme récente {request['home']} : {hp['form']} ; {hp['goals_for']:.2f} but(s) marqué(s), {hp['goals_against']:.2f} encaissé(s) par match (moyennes pondérées).",
        f"Forme récente {request['away']} : {ap['form']} ; {ap['goals_for']:.2f} but(s) marqué(s), {ap['goals_against']:.2f} encaissé(s) par match (moyennes pondérées).",
        f"Tirs cadrés : {hp['shots_on_target']:.1f} contre {ap['shots_on_target']:.1f}. Force des adversaires et domicile inclus dans le modèle.",
        'Les xG disponibles sont affichés, mais exclus du modèle faute de couverture historique homogène.',
        'Ajustement offensif de composition limité à ±15 % des buts attendus, selon la production buts + passes des titulaires comparée aux trois dernières compositions. Heuristique non validée, aucune valeur marchande utilisée.']
    return result
