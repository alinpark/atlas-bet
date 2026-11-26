import os
import unittest
from unittest.mock import patch
from fastapi.testclient import TestClient
from app.main import app


class EngineContractTest(unittest.TestCase):
    def test_sport_analysis_auth_and_schema(self):
        client=TestClient(app)
        sample={'competition':'soccer_epl','home':'Arsenal','away':'Chelsea','startsAt':'2026-12-01T20:00:00Z'}
        with patch.dict(os.environ,{'BET_ENGINE_TOKEN':'sport-test'}),patch('app.free_football.analyze',return_value={'status':'INSUFFICIENT_DATA'}) as analyze:
            self.assertEqual(client.post('/analysis/football',json=sample).status_code,401)
            self.assertEqual(client.post('/analysis/football',json=sample,headers={'X-Engine-Token':'sport-test'}).status_code,200)
            self.assertEqual(analyze.call_count,1)
            self.assertEqual(client.post('/analysis/football',json={**sample,'startsAt':'2026-12-01'},headers={'X-Engine-Token':'sport-test'}).status_code,422)

    def test_authenticated_sport_models_and_timestamp_guard(self):
        client = TestClient(app)
        sample = {
            "eventId": "DEMO-1", "featureTimestamp": "2026-11-17T12:00:00Z",
            "startsAt": "2026-11-17T14:00:00+01:00", "homeRating": 1540,
            "awayRating": 1460, "provenance": "DEMO",
        }
        with patch.dict(os.environ, {"BET_ENGINE_TOKEN": "dummy-engine-test"}):
            self.assertEqual(client.post("/predict/tennis", json=sample).status_code, 401)
            headers = {"X-Engine-Token": "dummy-engine-test"}
            for sport, count in (("soccer", 3), ("basketball", 2), ("tennis", 2)):
                response = client.post(f"/predict/{sport}", json=sample, headers=headers)
                self.assertEqual(response.status_code, 200)
                payload = response.json()
                self.assertEqual(payload["modelStatus"], "UNVALIDATED")
                self.assertEqual(len(payload["outcomes"]), count)
                self.assertAlmostEqual(sum(item["p"] for item in payload["outcomes"]), 1)
                self.assertTrue(all(item["pLower"] is None for item in payload["outcomes"]))
            metrics = client.get("/models/metrics", headers=headers).json()
            self.assertEqual(metrics["modelStatus"], "UNVALIDATED")
            sample["featureTimestamp"] = sample["startsAt"]
            self.assertEqual(client.post("/predict/soccer", json=sample, headers=headers).status_code, 422)
