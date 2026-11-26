import copy
import json
import unittest
from datetime import datetime, timezone, timedelta
from pathlib import Path
from unittest.mock import patch

from app import free_football as source
from app.sport_model import State, parse_csv, probabilities, team_key

NOW=datetime(2026,11,26,12,tzinfo=timezone.utc)
REQUEST=dict(competition='soccer_spain_la_liga',home='Barcelona',away='Real Madrid',startsAt='2026-11-29T15:00:00Z')


def populated():
    state=State()
    for i in range(16):
        state.update(dict(date=(NOW-timedelta(days=3*(16-i))).date().isoformat(),league='SP1',
                          home=team_key('Barcelona'),away=team_key('Real Madrid'),hg=2,ag=0,hs=7,ass=2,hxg=2.,axg=.6))
    return state


def ctx():
    return {'lineup_status':'PREDICTED', 'home':{'attack_factor':.9,'impact_status':'HEURISTIC'},
            'away':{'attack_factor':1.,'impact_status':'HEURISTIC'}}


class SportModelTest(unittest.TestCase):
    def analyze(self, req=None, state=None, context=None):
        with patch.object(source,'load_state',return_value=(state or populated(),[],[])), \
             patch.object(source,'find_match',return_value=({'id':1},None)), \
             patch.object(source,'context',return_value=context or ctx()):
            return source.analyze(req or REQUEST,NOW)

    def test_probabilities_normalized_and_symmetric(self):
        for h,a in [(1.4,1.4),(.2,6),(6,.2)]:
            p=probabilities(h,a);self.assertAlmostEqual(sum(p.values()),1)
            self.assertAlmostEqual(p['home'],probabilities(a,h)['away'])

    def test_target_same_day_and_future_are_excluded(self):
        state=populated();before=state.profile(team_key('Barcelona'),'2026-11-26')
        state.history[team_key('Barcelona')].append(dict(date='2026-11-26',gf=20,ga=0,sf=40,sa=0,xg=10,home=True))
        state.history[team_key('Barcelona')].append(dict(date='2027-01-01',gf=20,ga=0,sf=40,sa=0,xg=10,home=True))
        self.assertEqual(before,state.profile(team_key('Barcelona'),'2026-11-26'))

    def test_csv_rejects_unfinished_and_nonfinite_stats(self):
        raw='Date,HomeTeam,AwayTeam,FTHG,FTAG,HST,AST\n25/11/26,A,B,2,0,nan,3\n28/11/26,A,B,,,,\n'
        rows=parse_csv(raw,'SP1');self.assertEqual(len(rows),1);self.assertIsNone(rows[0]['hs'])

    def test_prices_cannot_change_forecast(self):
        a=self.analyze();b=self.analyze({**REQUEST,'odds':{'home':1000,'away':1.01,'draw':500}})
        self.assertEqual(a['probabilities'],b['probabilities']);self.assertEqual(a['lean'],'home')

    def test_lineup_changes_probability_without_claiming_validation(self):
        result=self.analyze()
        self.assertLess(result['probabilities']['home'],result['base_probabilities']['home'])
        self.assertEqual(result['decision'],'ABSTAIN');self.assertEqual(result['stake'],0)
        self.assertEqual(result['model_status'],'UNVALIDATED')

    def test_missing_history_does_not_fall_back_to_odds(self):
        r=self.analyze(state=State());self.assertEqual(r['status'],'INSUFFICIENT_DATA');self.assertFalse(r['probabilities'])

    def test_kickoff_blocks_requests_before_network(self):
        with patch.object(source,'load_state') as fetch:
            r=source.analyze({**REQUEST,'startsAt':NOW.isoformat()},NOW)
            self.assertFalse(r['probabilities']);fetch.assert_not_called()

    def test_feed_failure_is_explicit_but_preserves_statistical_analysis(self):
        with patch.object(source,'load_state',return_value=(populated(),[],[])),patch.object(source,'find_match',side_effect=ValueError('Unavailable')):
            r=source.analyze(REQUEST,NOW)
            self.assertEqual(r['status'],'ANALYZED');self.assertIsNone(r['context']);self.assertIn('Unavailable',r['reasons'])
            self.assertEqual(r['base_probabilities'],r['probabilities'])

    def test_training_has_frozen_holdout_and_no_price_features(self):
        artifact=json.loads(Path('ai-engine/app/sport_weights.json').read_text())
        self.assertLess(artifact['trained_through'],artifact['validation']['from'])
        self.assertGreater(artifact['validation']['overall']['matches'],3000)
        self.assertFalse(any('odds' in x or 'price' in x for x in artifact['features']))

    def test_strict_fixture_identity_orientation_and_time(self):
        match={'id':1,'home':{'name':'Barcelona'},'away':{'name':'Real Madrid'},'status':{'utcTime':REQUEST['startsAt']}}
        data={'leagues':[{'primaryId':87,'matches':[match]}]}
        with patch.object(source,'fetch',return_value=(data,NOW.isoformat())):
            self.assertEqual(source.find_match(REQUEST['competition'],'Barcelona','Real Madrid',source.utc(REQUEST['startsAt']))[0]['id'],1)
            with self.assertRaises(ValueError): source.find_match(REQUEST['competition'],'Real Madrid','Barcelona',source.utc(REQUEST['startsAt']))
            with self.assertRaises(ValueError): source.find_match(REQUEST['competition'],'Barcelona','Real Madrid',source.utc(REQUEST['startsAt'])+timedelta(hours=2))
            data['leagues'][0]['matches'].append(copy.deepcopy(match))
            with self.assertRaises(ValueError): source.find_match(REQUEST['competition'],'Barcelona','Real Madrid',source.utc(REQUEST['startsAt']))

    def test_predicted_eleven_never_confirmed_and_missing_absences_unknown(self):
        detail={'general':{'matchId':'1','matchTimeUTCDate':REQUEST['startsAt'],'started':False},
                'content':{'lineup':{'lineupType':'predicted',**{s+'Team':{'id':i,'starters':[{'id':j,'name':str(j)} for j in range(11)]} for s,i in [('home',2),('away',3)]}}}}
        match={'id':1,'home':{'id':2},'away':{'id':3}}
        def get(url,*_):
            if 'teams?' in url: raise ValueError('no squad')
            return detail,NOW.isoformat()
        with patch.object(source,'fetch',side_effect=get):
            r=source.context(match,source.utc(REQUEST['startsAt']),NOW)
            self.assertEqual(r['lineup_status'],'PREDICTED');self.assertEqual(r['home']['absence_coverage'],'UNKNOWN')
            detail['content']['lineup']['lineupType']='standard'
            self.assertEqual(source.context(match,source.utc(REQUEST['startsAt']),NOW)['lineup_status'],'CONFIRMED')
            detail['content']['lineup']['homeTeam']['starters'].pop()
            self.assertEqual(source.context(match,source.utc(REQUEST['startsAt']),NOW)['lineup_status'],'UNKNOWN')

    def test_postmatch_lineup_cannot_be_used_as_prematch_context(self):
        with patch.object(source,'fetch',return_value=({'general':{'started':True}},NOW.isoformat())):
            with self.assertRaises(ValueError): source.context({'id':1},source.utc(REQUEST['startsAt']),NOW)

    def test_injuries_available_even_without_predicted_lineup(self):
        detail={'general':{'matchId':'1','matchTimeUTCDate':REQUEST['startsAt']},'content':{}}
        def get(url,*_):
            if 'teams?' in url:
                ident=int(url.split('=')[-1])
                return {'details':{'id':ident},'squad':{'squad':[{'title':'defenders','members':[
                    {'id':42,'name':'Injured player','injury':{'expectedReturn':'Doubtful'}}]}]}},NOW.isoformat()
            return detail,NOW.isoformat()
        with patch.object(source,'fetch',side_effect=get):
            r=source.context({'id':1,'home':{'id':2},'away':{'id':3}},source.utc(REQUEST['startsAt']),NOW)
            self.assertEqual(r['lineup_status'],'UNKNOWN')
            self.assertEqual(r['home']['absence_coverage'],'INJURIES_ONLY_NOT_EXHAUSTIVE')
            self.assertTrue(r['home']['absent'][0]['uncertain'])
            self.assertEqual(r['home']['attack_factor'],1)

    def test_loader_excludes_current_day_and_future_results(self):
        raw='Date,HomeTeam,AwayTeam,FTHG,FTAG,HST,AST\n25/11/26,A,B,2,0,7,3\n26/11/26,A,B,20,0,50,3\n27/11/26,A,B,20,0,50,3\n'
        with patch.object(source,'fetch',return_value=(raw,NOW.isoformat())):
            state,_,_=source.load_state(NOW)
            self.assertTrue(all(r['date']=='2026-11-25' for r in state.history['a']))

    def test_all_six_competitions_declared(self):
        self.assertEqual(len(source.COMPETITIONS),6)
        self.assertEqual(source.COMPETITIONS['soccer_uefa_europa_league'][0],73)


if __name__=='__main__':unittest.main()
