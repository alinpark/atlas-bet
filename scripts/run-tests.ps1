python -m unittest -v
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
mvn -B -f backend/pom.xml test
exit $LASTEXITCODE
