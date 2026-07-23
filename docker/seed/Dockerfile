# Lightweight one-shot seeding container for the dev stack.
# Waits for fams-api to become healthy (via compose depends_on), then runs
# scripts/seed.sh against it. Not used by production compose files.
FROM python:3.12-slim

RUN apt-get update -qq \
    && apt-get install -y --no-install-recommends bash curl postgresql-client \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /scripts
ENTRYPOINT ["bash", "seed.sh"]
