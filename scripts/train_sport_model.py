"""Reproducible chronological holdout. Run with repository and ai-engine on sys.path.

Fit: 2019-2024 (2018 warmup). Locked holdout: 2025-01-01..2026-06-30.
The coefficients exported are exactly those evaluated, never refit on holdout.
"""
import hashlib
import json
import math
from itertools import groupby
from pathlib import Path
import urllib.request

from app.sport_model import State, FEATURES, VERSION, fit, goal_rate, parse_csv, probabilities


def train():
    cache=Path('backend/target/sport-training');cache.mkdir(parents=True,exist_ok=True)
    rows=[];sources=[]
    for league in ['F1','E0','D1','SP1','I1','N1','P1','B1']:
        for year in range(18,26):
            season=f'{year:02d}{year+1:02d}'; path=cache/f'{league}-{season}.csv'
            url=f'https://www.football-data.co.uk/mmz4281/{season}/{league}.csv'
            if not path.exists():
                with urllib.request.urlopen(url,timeout=25) as response: path.write_bytes(response.read())
            raw=path.read_bytes();sources.append({'url':url,'sha256':hashlib.sha256(raw).hexdigest()})
            rows.extend(parse_csv(raw.decode('utf-8-sig'),league))
    state=State();training=[];holdout=[]; seen=set()
    for day,group in groupby(sorted(rows,key=lambda r:r['date']),key=lambda r:r['date']):
        matches=list(group)
        for r in matches:
            ident=(r['league'],day,r['home'],r['away'])
            if ident in seen: raise ValueError('Duplicate fixture')
            seen.add(ident)
            hx,ax,hp,ap=state.features(r['home'],r['away'],day)
            if min(hp['matches'],ap['matches'])<8 or min(hp['shots_matches'],ap['shots_matches'])<6: continue
            if '2019-01-01'<=day<'2025-01-01': training.extend([(hx,r['hg']),(ax,r['ag'])])
            elif '2025-01-01'<=day<='2026-06-30': holdout.append((r,hx,ax))
        for r in matches: state.update(r)
    weights=fit(training)
    def metrics(items):
        n=len(items)
        if not n: return {'matches':0}
        loss=brier=hits=base_loss=base_brier=0.;bins=[[] for _ in range(10)]
        for r,hx,ax in items:
            p=probabilities(goal_rate(weights,hx),goal_rate(weights,ax))
            # Fitted intercept + home only baseline, trained on identical training set.
            base=probabilities(goal_rate(base_weights,[1,1]),goal_rate(base_weights,[1,0]))
            actual='home' if r['hg']>r['ag'] else 'away' if r['hg']<r['ag'] else 'draw'
            loss-=math.log(max(1e-15,p[actual]));base_loss-=math.log(base[actual])
            brier+=sum((v-int(k==actual))**2 for k,v in p.items());base_brier+=sum((v-int(k==actual))**2 for k,v in base.items())
            pick=max(p,key=p.get);hits+=pick==actual
            for k,v in p.items(): bins[min(9,int(v*10))].append((v,int(k==actual)))
        ece=sum(abs(sum(p-y for p,y in b)) for b in bins)/(3*n)
        return dict(matches=n,log_loss=loss/n,brier=brier/n,accuracy=hits/n,ece=ece,
                    baseline_log_loss=base_loss/n,baseline_brier=base_brier/n)
    # fit() supports dynamic feature dimensions with explicit initial values below.
    baseline=[([x[0],x[1]],y) for x,y in training]
    base_weights=fit(baseline)
    validation={'from':'2025-01-01','through':'2026-06-30','method':'Chronological holdout; same-day batches; no odds inputs',
                'overall':metrics(holdout),'by_league':{l:metrics([x for x in holdout if x[0]['league']==l]) for l in ['F1','E0','D1','SP1','I1','N1','P1','B1']},
                'scope':'Statistical base only. No historical lineup/injury test; no profitability claim.'}
    artifact={'version':VERSION,'features':FEATURES,'weights':weights,'trained_through':'2024-12-31',
              'training_matches':len(training)//2,'validation':validation}
    Path('ai-engine/app/sport_weights.json').write_text(json.dumps(artifact,indent=2)+'\n',encoding='utf-8')
    report={**artifact,'sources':sources}
    Path('docs/validation/SPORT_MODEL_V1.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(artifact,indent=2))


if __name__=='__main__': train()
