COMPOSE      = docker compose -f docker-compose.yml
COMPOSE_DEV  = $(COMPOSE) -f docker-compose.dev.yml

.PHONY: dev prod stop restart-api logs logs-api logs-db logs-redis \
        shell-api shell-db ps clean help

## ── Dev (source mounted, maven run) ──────────────────────────────
dev:
	$(COMPOSE_DEV) up --build

dev-d:
	$(COMPOSE_DEV) up --build -d

## ── Prod (built jar image) ───────────────────────────────────────
prod:
	$(COMPOSE) up --build -d

## ── Stop everything ──────────────────────────────────────────────
stop:
	$(COMPOSE_DEV) down

stop-v:
	$(COMPOSE_DEV) down -v

## ── Restart individual services ──────────────────────────────────
restart-api:
	$(COMPOSE_DEV) restart fams-api

restart-db:
	$(COMPOSE_DEV) restart fams-postgres

restart-redis:
	$(COMPOSE_DEV) restart fams-redis

## ── Logs ─────────────────────────────────────────────────────────
logs:
	$(COMPOSE_DEV) logs -f

logs-api:
	$(COMPOSE_DEV) logs -f fams-api

logs-db:
	$(COMPOSE_DEV) logs -f fams-postgres

logs-redis:
	$(COMPOSE_DEV) logs -f fams-redis

## ── Shell access ─────────────────────────────────────────────────
shell-api:
	docker exec -it fams-api bash

shell-db:
	docker exec -it fams-postgres psql -U $${DB_USER:-fams_user} -d $${DB_NAME:-fams_db}

## ── Status ───────────────────────────────────────────────────────
ps:
	$(COMPOSE_DEV) ps

## ── Clean build cache ────────────────────────────────────────────
clean:
	$(COMPOSE_DEV) down -v --remove-orphans
	docker image prune -f

## ── Help ─────────────────────────────────────────────────────────
help:
	@echo ""
	@echo "  make dev           Start all services in dev mode (foreground)"
	@echo "  make dev-d         Start all services in dev mode (background)"
	@echo "  make prod          Build and start with production image"
	@echo "  make stop          Stop all services"
	@echo "  make stop-v        Stop and remove volumes"
	@echo "  make restart-api   Restart Java API container"
	@echo "  make logs          Tail all logs"
	@echo "  make logs-api      Tail Java API logs"
	@echo "  make shell-api     Open bash in Java API container"
	@echo "  make shell-db      Open psql in Postgres container"
	@echo "  make ps            Show container status"
	@echo "  make clean         Remove containers, volumes, dangling images"
	@echo ""
