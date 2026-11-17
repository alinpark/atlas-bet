import os
import unittest
from unittest.mock import patch
from fastapi.testclient import TestClient
from app.main import app


class EngineContractTest(unittest.TestCase):
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
