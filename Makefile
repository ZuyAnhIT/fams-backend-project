COMPOSE      = docker compose -f docker-compose.yml
COMPOSE_DEV  = $(COMPOSE) -f docker-compose.dev.yml

COMPOSE_FULL     = docker compose -f docker-compose.full.yml
COMPOSE_FULL_DEV = $(COMPOSE_FULL) -f docker-compose.dev.yml

.PHONY: dev prod stop restart-api logs logs-api logs-db logs-redis \
        shell-api shell-db ps clean \
        full full-d full-dev full-dev-d full-stop full-stop-v \
        restart-ai logs-ai shell-ai \
        help

## ── Dev (source mounted, maven run) ──────────────────────────────
dev:
	$(COMPOSE_DEV) up --build

dev-d:
	$(COMPOSE_DEV) up --build -d

## ── Prod (built jar image) ───────────────────────────────────────
prod:
	$(COMPOSE) up --build -d

## ── First-time setup (start + seed) ─────────────────────────────
setup:
	$(COMPOSE_DEV) up --build -d
	bash scripts/dev-start.sh

## ── Seed demo data ───────────────────────────────────────────────
seed:
	bash scripts/seed.sh

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

## ── Full stack (Java + AI service) ──────────────────────────────
## NOTE: Face ID and liveness endpoints in the Java API require the
##       full stack. Java-only mode will return errors for those calls.

full:
	$(COMPOSE_FULL) up --build

full-d:
	$(COMPOSE_FULL) up --build -d

full-dev:
	$(COMPOSE_FULL_DEV) up --build

full-dev-d:
	$(COMPOSE_FULL_DEV) up --build -d

full-stop:
	$(COMPOSE_FULL) down

full-stop-v:
	$(COMPOSE_FULL) down -v

## ── Full stack — individual service controls ─────────────────────
restart-ai:
	$(COMPOSE_FULL) restart fams-ai

logs-ai:
	$(COMPOSE_FULL) logs -f fams-ai

shell-ai:
	docker exec -it fams-ai bash

## ── Help ─────────────────────────────────────────────────────────
help:
	@echo ""
	@echo "  ── Java backend only (no AI service) ──────────────────────────"
	@echo "  make setup         First-time setup: start services + seed demo data"
	@echo "  make dev           Start in dev mode (foreground)"
	@echo "  make dev-d         Start in dev mode (background)"
	@echo "  make seed          Seed demo tenants and employees"
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
	@echo "  ── Full stack: Java + AI service (Face ID / liveness) ─────────"
	@echo "  !! Face ID and liveness API calls REQUIRE the full stack.      !!"
	@echo "  !! Java-only mode will return errors for those endpoints.      !!"
	@echo ""
	@echo "  make full          Start full stack (foreground)"
	@echo "  make full-d        Start full stack (background)"
	@echo "  make full-dev      Start full stack in dev mode (foreground)"
	@echo "  make full-dev-d    Start full stack in dev mode (background)"
	@echo "  make full-stop     Stop full stack"
	@echo "  make full-stop-v   Stop full stack and remove volumes"
	@echo "  make restart-ai    Restart AI service container"
	@echo "  make logs-ai       Tail AI service logs"
	@echo "  make shell-ai      Open bash in AI service container"
	@echo ""
