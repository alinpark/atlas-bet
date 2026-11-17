FROM python:3.12-slim
WORKDIR /app
RUN mkdir /data && chown 10001:10001 /data
COPY atlas_bet.py ledger.py models.py providers.py workflow.py telegram_bot.py worker.py ./
USER 10001:10001
ENV SPORTS_DB=/data/atlas-bet.sqlite3
ENTRYPOINT ["python", "-m", "atlas_bet"]
CMD ["demo"]



