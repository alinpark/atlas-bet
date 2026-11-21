"""Bounded, resumable discovery audit. Never downloads paid historical odds.

Run with THE_ODDS_API_KEY in the environment and --output outside the repository.
The journal reserves before sending, including failed requests. No keys in URLs/logs.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import urllib.error
import urllib.parse
import urllib.request

FR = ('betclic_fr', 'netbet_fr', 'pmu_fr', 'unibet_fr', 'winamax_fr')
BOOKS = FR + ('pinnacle',)
SPORT = 'soccer_france_ligue_one'
# Fixed before fetching: autumn and spring in four completed seasons.
DATES = ('2022-10-15', '2023-03-11', '2023-10-21', '2024-03-09',
         '2024-10-19', '2025-03-08', '2025-10-18', '2026-03-14')
# Second-stage discovery, chosen after presence metadata (never scores/odds):
# locate the apparent FR coverage transition and check the current partial season.
FOLLOWUP_DATES = ('2024-12-14', '2025-01-18', '2025-05-10', '2025-08-30', '2026-10-17')


class Discovery:
    def __init__(self, folder, key, limit=40, floor=420):
        self.folder = Path(folder)
        self.folder.mkdir(parents=True, exist_ok=True)
        self.key, self.limit, self.floor = key, min(limit, 40), floor
        self.db = sqlite3.connect(self.folder / 'audit.sqlite3', timeout=30)
        self.db.execute('CREATE TABLE IF NOT EXISTS calls (id TEXT PRIMARY KEY, path TEXT, '
                        'params TEXT, status INTEGER, remaining INTEGER, charged INTEGER, body TEXT)')
        self.db.commit()

    def fetch(self, path, params):
        canonical = json.dumps(params, sort_keys=True)
        identity = hashlib.sha256((path + canonical).encode()).hexdigest()
        # Process-safe reservation. Failed/interrupted calls are not silently retried.
        self.db.execute('BEGIN IMMEDIATE')
        row = self.db.execute('SELECT status,body FROM calls WHERE id=?', (identity,)).fetchone()
        if row:
            self.db.commit()
            if row[0] == 200:
                try:
                    return json.loads(row[1])
                except (TypeError, ValueError):
                    raise RuntimeError('Cached provider response unreadable; no retry') from None
            raise RuntimeError('Previous request failed/interrupted; explicit audit review required')
        spent = self.db.execute('SELECT count(*) FROM calls').fetchone()[0]
        remaining = self.db.execute('SELECT min(remaining) FROM calls').fetchone()[0]
        if spent >= self.limit or (remaining is not None and remaining <= self.floor):
            self.db.rollback()
            raise RuntimeError('Discovery budget or live-collection reserve reached')
        self.db.execute('INSERT INTO calls(id,path,params) VALUES (?,?,?)', (identity, path, canonical))
        self.db.commit()
        req = urllib.request.Request('https://api.the-odds-api.com' + path + '?' +
                                     urllib.parse.urlencode(params), headers={'x-api-key': self.key})
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                status, headers, body = response.status, response.headers, response.read().decode()
        except urllib.error.HTTPError as error:
            status, headers, body = error.code, error.headers, error.read().decode()
        except (urllib.error.URLError, TimeoutError):
            raise RuntimeError('Provider connection failed; reservation retained') from None
        remaining = headers.get('x-requests-remaining')
        charged = headers.get('x-requests-last')
        self.db.execute('UPDATE calls SET status=?,remaining=?,charged=?,body=? WHERE id=?',
                        (status, int(remaining) if remaining else None,
                         int(charged) if charged else None, body, identity))
        self.db.commit()
        try:
            data = json.loads(body)
            if not isinstance(data, dict):
                raise ValueError('Expected object')
        except ValueError:
            self.db.execute('UPDATE calls SET status=-1 WHERE id=?', (identity,))
            self.db.commit()
            raise RuntimeError('Provider response unreadable; reservation retained') from None
        if status != 200:
            # Error code only: provider error strings could contain request credentials.
            raise RuntimeError('Provider HTTP %s (%s)' % (status, data.get('error_code', 'unspecified')))
        return data


def audit(client, followup=False):
    rows, error = [], None
    try:
        for day in DATES + (FOLLOWUP_DATES if followup else ()):
            stamp = day + 'T00:00:00Z'
            events = client.fetch('/v4/historical/sports/' + SPORT + '/events', {'date': stamp})
            cutoff = dt.datetime.fromisoformat(stamp.replace('Z', '+00:00'))
            # First two scheduled matches, not selected by score or odds.
            eligible = sorted((e for e in events.get('data', [])
                               if cutoff < dt.datetime.fromisoformat(e['commence_time'].replace('Z', '+00:00'))
                               <= cutoff + dt.timedelta(days=2)),
                              key=lambda e: (e['commence_time'], e['id']))[:2]
            if not eligible:
                rows.append({'date': day, 'status': 'NO_EVENTS_IN_SAMPLE_WINDOW'})
            for event in eligible:
                at = dt.datetime.fromisoformat(event['commence_time'].replace('Z', '+00:00')) - dt.timedelta(hours=1)
                markets = client.fetch('/v4/historical/sports/' + SPORT + '/events/' + event['id'] + '/markets',
                                       {'date': at.isoformat().replace('+00:00', 'Z'), 'bookmakers': ','.join(BOOKS)})
                present = sorted(b['key'] for b in markets.get('data', {}).get('bookmakers', [])
                                 if any(m['key'] == 'h2h' for m in b.get('markets', [])))
                rows.append({'date': day, 'event_id': event['id'], 'home': event['home_team'],
                             'away': event['away_team'], 'known_start': event['commence_time'],
                             'queried_at': at.isoformat(), 'h2h_present': present,
                             'candidate': 'pinnacle' in present and len(set(present) & set(FR)) >= 3})
    except RuntimeError as exc:
        error = str(exc)
    calls = client.db.execute('SELECT count(*),sum(charged),min(remaining) FROM calls').fetchone()
    report = {'experiment': 'hist-fr-pinnacle-v1', 'generated_at': dt.datetime.now(dt.timezone.utc).isoformat(),
              'dates_fixed_before_fetch': list(DATES), 'sport': SPORT,
              'followup_dates_after_presence_only': list(FOLLOWUP_DATES) if followup else [],
              'reserved_credits': calls[0], 'provider_charged_credits': calls[1],
              'provider_remaining': calls[2], 'cap': client.limit, 'reserve_floor': client.floor,
              'rows': rows, 'error': error,
              'decision': 'BLOCKED_NO_PURCHASE' if error else 'DISCOVERY_ONLY_REVIEW_REQUIRED',
              'limitations': ['Presence only; no proof of freshness or full-season coverage',
                              'First two scheduled matches per window: afternoon/early fixtures overrepresented',
                              'Stratified small sample; no ROI or CLV evaluated']}
    (client.folder / 'coverage-report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
    return report


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--output', required=True)
    p.add_argument('--reserve-floor', type=int, default=420)
    p.add_argument('--followup', action='store_true')
    args = p.parse_args()
    print(json.dumps(audit(Discovery(args.output, os.environ['THE_ODDS_API_KEY'], floor=args.reserve_floor), args.followup),
                     ensure_ascii=False, indent=2))
