FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    CODEWIKI_STORAGE_DIR=/app/storage \
    CODEWIKI_NO_KEYRING=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        git \
        libpq5 \
        nodejs \
        npm \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --upgrade pip \
    && pip install --requirement requirements.txt

COPY entrypoint.py analyze_worker.py parse_file_worker.py codewiki_patches.py .

RUN mkdir -p /app/storage /workspace/repositories

EXPOSE 8000

HEALTHCHECK NONE

CMD ["python", "entrypoint.py", "serve", "--host", "0.0.0.0", "--port", "8000"]
