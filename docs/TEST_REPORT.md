# Tests du 17 novembre 2026

- Python 3.12.10, python -m unittest -v : 15 tests réussis.
- Maven hors ligne, mvn -o -q -f backend/pom.xml test : 2 tests réussis.
- FastAPI, python -m unittest discover -s ai-engine -v dans image locale : 1 test réussi, trois modèles et contrôle d'accès.
- Compose config --quiet : code 0.
- Build ai-engine standard : échec de validation du certificat PyPI.
- E2E §8 complet : non exécuté. Smoke SQLite précédent : code 0, ingestion, simulation, règlement et persistance.
