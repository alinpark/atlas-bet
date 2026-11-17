FROM python:3.12-slim
# OFFLINE_WHEELS=1 : installation depuis ai-engine/wheels (préparé par scripts/prepare-build),
# utile quand un proxy ou un antivirus intercepte TLS dans les conteneurs. La vérification
# des certificats n'est jamais désactivée.
ARG OFFLINE_WHEELS=0
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY ai-engine/requirements.txt ai-engine/wheel[s] /tmp/deps/
RUN if [ "$OFFLINE_WHEELS" = "1" ]; then \
      pip install --no-cache-dir --no-index --find-links /tmp/deps -r /tmp/deps/requirements.txt; \
    else \
      pip install --no-cache-dir -r /tmp/deps/requirements.txt; \
    fi && rm -rf /tmp/deps
COPY ai-engine/app ./app
COPY models.py workflow.py providers.py ./
USER 10001:10001
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
