#!/usr/bin/env sh
set -eu
python -m unittest -v
mvn -B -f backend/pom.xml test
