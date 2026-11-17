#!/usr/bin/env sh
# Construit le jar du backend et télécharge les wheels Linux du moteur (voir prepare-build.ps1).
set -eu
cd "$(dirname "$0")/.."
if [ "${1:-}" = "--skip-tests" ]; then mvn -B -q -f backend/pom.xml -DskipTests package
else mvn -B -q -f backend/pom.xml package; fi
mkdir -p ai-engine/wheels
python3 -m pip download -q --only-binary=:all: --platform manylinux2014_x86_64 \
  --platform manylinux_2_17_x86_64 --python-version 3.12 --implementation cp \
  -d ai-engine/wheels -r ai-engine/requirements.txt
echo "Préparation terminée."
