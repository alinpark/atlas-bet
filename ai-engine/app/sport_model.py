"""Odds-free, causal football features and fitted Poisson goal model (stdlib only).

No target-match statistics, bookmaker prices, or player market values are inputs.
All matches on a date are predicted before that date's results enter the state.
"""
import csv
import io
import math
import unicodedata
from collections import defaultdict
from datetime import date

VERSION = 'sport-form-v1'
FEATURES = ['intercept', 'home', 'attack_goals', 'opponent_conceded',
            'attack_shots_on_target', 'opponent_shots_on_target', 'elo_difference', 'rest_difference']


def key(name):
    return ''.join(c for c in unicodedata.normalize('NFKD', name).lower() if c.isalnum() and not unicodedata.combining(c))


ALIASES = {}
for names in [
    ['Paris SG', 'Paris Saint-Germain', 'PSG'], ['Marseille', 'Olympique Marseille'],
    ['Lyon', 'Olympique Lyonnais'], ['St Etienne', 'Saint-Etienne'],
    ['Man City', 'Manchester City'], ['Man United', 'Manchester United'],
    ['Nottm Forest', 'Nottingham Forest'], ['Wolves', 'Wolverhampton Wanderers'],
    ['Tottenham', 'Tottenham Hotspur'], ['Newcastle', 'Newcastle United'],
    ['Leeds', 'Leeds United'], ['West Ham', 'West Ham United'],
    ['Brighton', 'Brighton and Hove Albion', 'Brighton & Hove Albion'],
    ['Ipswich', 'Ipswich Town'], ['Coventry', 'Coventry City'],
    ['Ath Madrid', 'Atletico Madrid', 'Atlético Madrid'], ['Ath Bilbao', 'Athletic Club', 'Athletic Bilbao'],
    ['Sociedad', 'Real Sociedad'], ['Betis', 'Real Betis'], ['Alaves', 'Deportivo Alavés'],
    ['La Coruna', 'Deportivo A Coruña', 'Deportivo La Coruna'], ['Santander', 'Racing Santander'],
    ['Celta', 'Celta Vigo'], ['Vallecano', 'Rayo Vallecano'],
    ['Bayern Munich', 'Bayern München'], ['Dortmund', 'Borussia Dortmund'],
    ["M'gladbach", 'Borussia Mönchengladbach', 'Borussia Monchengladbach'],
    ['Ein Frankfurt', 'Eintracht Frankfurt'], ['Leverkusen', 'Bayer Leverkusen'],
    ['FC Koln', '1. FC Köln', 'Cologne'], ['Mainz', 'Mainz 05'],
    ['RB Leipzig', 'RasenBallsport Leipzig'], ['St Pauli', 'St. Pauli'],
    ['Inter', 'Internazionale', 'Inter Milan'], ['Milan', 'AC Milan'],
    ['Verona', 'Hellas Verona'], ['PSV Eindhoven', 'PSV'], ['AZ Alkmaar', 'AZ'],
    ['Bournemouth','AFC Bournemouth'], ['Hull','Hull City'], ['Monaco','AS Monaco'],
    ['Le Mans','Le Mans FC'], ['Lens','RC Lens'], ['Elche','Elche CF'], ['Osasuna','CA Osasuna'],
    ['Espanol','Espanyol'], ['Hamburg','Hamburger SV'], ['Paderborn','SC Paderborn'],
    ['Schalke 04','FC Schalke 04'], ['Mainz','Mainz 05','FSV Mainz 05'],
    ['Hoffenheim','TSG Hoffenheim'], ['Stuttgart','VfB Stuttgart'], ['Freiburg','SC Freiburg'],
    ['Santander','Racing Santander','Real Racing Club de Santander'], ['Nijmegen','NEC Nijmegen'],
    ['St Gilloise','Union Saint-Gilloise','Union St.Gilloise'], ['Sp Lisbon','Sporting CP','Sporting Lisbon'],
    ['Sp Braga','Braga','Sporting Braga'], ['Omonoia FC','Omonia Nicosia'], ['Lillestrom','Lillestrøm'],
    ['Sturm Graz','SK Sturm Graz'], ['Ferencvaros','Ferencvaros TC'], ['Besiktas','Besiktas JK'],
    ['Olympiakos Piraeus','Olympiacos'], ['Levski Sofia','PFC Levski Sofia'],
    ['Hapoel Beer Sheva',"Hapoel Be'er Sheva"], ['Ararat Armenia','FC Ararat-Armenia']]:
    for n in names:
        ALIASES[key(n)] = key(names[0])


def team_key(name):
    return ALIASES.get(key(name), key(name))


def parse_csv(text, league):
    rows = []
    for r in csv.DictReader(io.StringIO(text.lstrip('\ufeff'))):
        try:
            d, m, y = r['Date'].split('/')
            day = date(int(y) + (2000 if len(y) == 2 else 0), int(m), int(d)).isoformat()
            hg, ag = int(r['FTHG']), int(r['FTAG'])
            if min(hg, ag) < 0 or max(hg, ag) > 30:
                continue
            def number(k):
                try:
                    v = float(r[k]); return v if math.isfinite(v) and v >= 0 else None
                except (KeyError, ValueError, TypeError):
                    return None
            rows.append(dict(date=day, league=league, home=team_key(r['HomeTeam']), away=team_key(r['AwayTeam']),
                             hg=hg, ag=ag, hs=number('HST'), ass=number('AST'), hxg=number('HxG'), axg=number('AxG')))
        except (KeyError, ValueError, TypeError):
            continue
    return rows


class State:
    def __init__(self):
        self.history = defaultdict(list)
        self.elo = defaultdict(lambda: 1500.)
        self.leagues = {}

    def update(self, r):
        h, a = r['home'], r['away']
        expectation = 1 / (1 + 10 ** ((self.elo[a] - self.elo[h] - 65) / 400))
        actual = 1 if r['hg'] > r['ag'] else 0 if r['hg'] < r['ag'] else .5
        delta = 20 * (actual - expectation)
        self.elo[h] += delta; self.elo[a] -= delta
        for side, t in [(True, h), (False, a)]:
            self.leagues[t] = r['league']
            self.history[t].append(dict(date=r['date'], gf=r['hg' if side else 'ag'], ga=r['ag' if side else 'hg'],
                                        sf=r['hs' if side else 'ass'], sa=r['ass' if side else 'hs'],
                                        xg=r['hxg' if side else 'axg'], home=side))
            self.history[t] = self.history[t][-24:]

    def profile(self, team, day):
        # No same-day results: robust to unknown CSV kickoff times.
        rows = [r for r in self.history[team] if r['date'] < day and
                (date.fromisoformat(day) - date.fromisoformat(r['date'])).days <= 370][-16:]
        def average(field, prior):
            values = [(r[field], .90 ** i) for i, r in enumerate(reversed(rows)) if r[field] is not None]
            return (sum(v*w for v,w in values)+4*prior)/(sum(w for _,w in values)+4)
        return dict(matches=len(rows), goals_for=average('gf', 1.4), goals_against=average('ga', 1.4),
                    shots_on_target=average('sf', 4.4), shots_conceded=average('sa', 4.4),
                    shots_matches=sum(r['sf'] is not None and r['sa'] is not None for r in rows),
                    xg=round(sum(r['xg'] for r in rows if r['xg'] is not None)/sum(r['xg'] is not None for r in rows), 2)
                       if any(r['xg'] is not None for r in rows) else None,
                    xg_matches=sum(r['xg'] is not None for r in rows),
                    rest_days=(date.fromisoformat(day)-date.fromisoformat(rows[-1]['date'])).days if rows else None,
                    last_match=rows[-1]['date'] if rows else None, elo=self.elo[team],
                    form=''.join('V' if r['gf']>r['ga'] else 'N' if r['gf']==r['ga'] else 'D' for r in rows[-5:]))

    def features(self, h, a, day):
        hp, ap = self.profile(h, day), self.profile(a, day)
        def row(p, q, home):
            return [1, float(home), math.log(p['goals_for']/1.4), math.log(q['goals_against']/1.4),
                    math.log(p['shots_on_target']/4.4), math.log(q['shots_conceded']/4.4),
                    (p['elo']-q['elo'])/400, (min(p['rest_days'] or 7,14)-min(q['rest_days'] or 7,14))/7]
        return row(hp, ap, True), row(ap, hp, False), hp, ap


def goal_rate(weights, x):
    return math.exp(max(-1.8, min(1.8, sum(w*v for w,v in zip(weights,x)))))


def probabilities(home_rate, away_rate):
    def poisson(rate):
        p = [math.exp(-rate)]
        for k in range(1, 31): p.append(p[-1]*rate/k)
        return p
    h, a = poisson(home_rate), poisson(away_rate)
    p = {'home': 0., 'draw': 0., 'away': 0.}
    for i, hi in enumerate(h):
        for j, aj in enumerate(a): p['home' if i>j else 'away' if i<j else 'draw'] += hi*aj
    total=sum(p.values())
    return {k:v/total for k,v in p.items()}


def fit(samples, epochs=150):
    weights=[.15,.20]+[0.]*(len(samples[0][0])-2)
    for _ in range(epochs):
        grad=[0.]*len(weights)
        for x,y in samples:
            error=goal_rate(weights,x)-y
            for j,v in enumerate(x): grad[j]+=error*v
        for j in range(len(weights)):
            weights[j]-=.08*(grad[j]/len(samples)+(.03*weights[j] if j>1 else 0))
    return weights
