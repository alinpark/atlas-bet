#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
export PYTHONDONTWRITEBYTECODE=1
python3 -m unittest -v
PYTHONPATH=.:ai-engine python3 -m unittest discover -s ai-engine -t ai-engine -v
mvn -B -f backend/pom.xml test
echo "Tous les tests ont réussi."
