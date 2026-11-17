FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
RUN mkdir /data && chown 10001:10001 /data
COPY atlas_bet.py ledger.py models.py providers.py workflow.py ./
USER 10001:10001
ENV SPORTS_DB=/data/atlas-bet-demo.sqlite3
ENTRYPOINT ["python", "-m", "atlas_bet"]
CMD ["demo"]
