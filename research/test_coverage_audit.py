import io
import json
import tempfile
import unittest
import urllib.error
from unittest.mock import patch

from research.coverage_audit import Discovery, audit


class Response(io.BytesIO):
    status = 200
    headers = {'x-requests-remaining': '450', 'x-requests-last': '1'}

    def __init__(self, data):
        super().__init__(json.dumps(data).encode())


class DiscoveryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.client = Discovery(self.tmp.name, 'secret-test-key', limit=2)

    def tearDown(self):
        self.client.db.close()
        self.tmp.cleanup()

    def test_cache_and_key_never_in_url_or_journal(self):
        with patch('urllib.request.urlopen', return_value=Response({'data': []})) as send:
            self.assertEqual(self.client.fetch('/events', {'date': 'one'}), {'data': []})
            self.assertEqual(self.client.fetch('/events', {'date': 'one'}), {'data': []})
            self.assertEqual(send.call_count, 1)
            self.assertNotIn('secret-test-key', send.call_args.args[0].full_url)
        self.assertNotIn('secret-test-key', str(self.client.db.execute('select * from calls').fetchall()))

    def test_limit_survives_restart(self):
        with patch('urllib.request.urlopen', side_effect=lambda *a, **k: Response({'data': []})) as send:
            for day in ['one', 'two']:
                self.client.fetch('/events', {'date': day})
            self.client.db.close()
            self.client = Discovery(self.tmp.name, 'secret-test-key', limit=2)
            with self.assertRaisesRegex(RuntimeError, 'budget'):
                self.client.fetch('/events', {'date': 'three'})
            self.assertEqual(send.call_count, 2)

    def test_timeout_reserves_credit_and_does_not_repeat(self):
        with patch('urllib.request.urlopen', side_effect=TimeoutError) as send:
            with self.assertRaisesRegex(RuntimeError, 'connection'):
                self.client.fetch('/events', {})
            with self.assertRaisesRegex(RuntimeError, 'Previous'):
                self.client.fetch('/events', {})
            self.assertEqual(send.call_count, 1)
        self.assertEqual(self.client.db.execute('select count(*) from calls').fetchone()[0], 1)

    def test_provider_floor_stops_next_request(self):
        response = Response({'data': []})
        response.headers = {'x-requests-remaining': '420'}
        with patch('urllib.request.urlopen', return_value=response) as send:
            self.client.fetch('/events', {'date': 'one'})
            with self.assertRaisesRegex(RuntimeError, 'reserve'):
                self.client.fetch('/events', {'date': 'two'})
            self.assertEqual(send.call_count, 1)

    def test_provider_error_redacts_message(self):
        error = urllib.error.HTTPError('https://provider', 401, 'unauthorized', {},
                                      io.BytesIO(b'{"error_code":"INVALID_KEY","message":"secret-test-key"}'))
        with patch('urllib.request.urlopen', side_effect=error):
            with self.assertRaisesRegex(RuntimeError, 'HTTP 401') as caught:
                self.client.fetch('/events', {})
        self.assertNotIn('secret-test-key', str(caught.exception))

    def test_empty_samples_report_incomplete_when_budget_expires(self):
        with patch('urllib.request.urlopen', side_effect=lambda *a, **k: Response({'data': []})):
            result = audit(self.client)
        self.assertEqual(result['decision'], 'BLOCKED_NO_PURCHASE')
        self.assertEqual(result['reserved_credits'], 2)
        self.assertEqual(len(result['rows']), 2)

    def test_cap_cannot_exceed_forty(self):
        self.client.db.close()
        self.client = Discovery(self.tmp.name, 'secret-test-key', limit=999)
        self.assertEqual(self.client.limit, 40)

    def test_non_json_is_retained_as_unreadable_without_retry(self):
        response = Response({})
        response = io.BytesIO(b'<html>provider error</html>')
        response.status = 200
        response.headers = {}
        with patch('urllib.request.urlopen', return_value=response) as send:
            with self.assertRaisesRegex(RuntimeError, 'unreadable'):
                self.client.fetch('/events', {})
            with self.assertRaisesRegex(RuntimeError, 'Previous'):
                self.client.fetch('/events', {})
            self.assertEqual(send.call_count, 1)


if __name__ == '__main__':
    unittest.main()
