FROM python:3.12-slim
WORKDIR /app
COPY ai-engine/pyproject.toml .
COPY ai-engine/app ./app
RUN pip install --no-cache-dir .
COPY models.py workflow.py providers.py ./
USER 10001:10001
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0"]
