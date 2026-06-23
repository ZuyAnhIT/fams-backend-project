# FAMS Backend

Faculty and Alumni Management System — multi-service backend built with Spring Boot, PostgreSQL (PostGIS), and Redis.

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) 24+
- [Docker Compose](https://docs.docker.com/compose/install/) v2 (bundled with Docker Desktop)
- `make`

No local Java or Maven required — everything runs inside Docker.

---

## Quick Start

### 1. Clone the repository

```bash
git clone <repo-url>
cd fams-backend-project
```

### 2. Create your `.env` file

```bash
cp .env.example .env
```

Open `.env` and fill in every field marked **REQUIRED** or **RANDOM**:

| Variable | How to set |
|---|---|
| `DB_PASSWORD` | Any strong password |
| `REDIS_PASSWORD` | `openssl rand -hex 24` |
| `JWT_SECRET` | `openssl rand -hex 32` |
| `OTP_DEV_FIXED_CODE` | Any 6-digit number (e.g. `123456`) |
| `OTP_RATE_LIMIT_MAX` | `10` (recommended for dev) |
| `GMAIL_USERNAME` | Your Gmail address |
| `GMAIL_APP_PASSWORD` | Gmail [App Password](https://myaccount.google.com/apppasswords) |
| `GOOGLE_CLIENT_ID` | From Google Cloud Console |

All other fields have safe defaults and can be left as-is for local development.

### 3. Start the backend (dev mode)

```bash
make dev-d
```

This command:
- Builds and starts PostgreSQL, Redis, and the API server
- Mounts source code into the container and runs the app via Maven
- Flyway migrations run automatically on startup

The first start downloads Docker images and Maven dependencies — this may take a few minutes.

### 4. Verify the system is live

```bash
curl http://localhost:8080/actuator/health
```

Expected response:

```json
{"status":"UP"}
```

---

## Running Tests

All tests use `curl` and require a running backend.

**Run the full test suite:**

```bash
BASE_URL=http://localhost:8080 bash tests/run_all.sh
```

**Run a single test suite:**

```bash
BASE_URL=http://localhost:8080 bash tests/auth/test_login.sh
```

> Note: Manual test scripts (files containing `manual` in the name) are excluded from `run_all.sh` and must be run individually.

---

## Services & Ports

| Service | Host Port | Container | Description |
|---|---|---|---|
| API Server | 8080 | `fams-api` | Spring Boot REST API |
| PostgreSQL | 5433 | `fams-postgres` | Database with PostGIS |
| Redis | 6379 | `fams-redis` | Cache / session store |

Ports can be changed via `DB_EXPOSE_PORT`, `REDIS_EXPOSE_PORT`, and `API_EXPOSE_PORT` in `.env`.

---

## Common Commands

```bash
make dev-d         # Start all services in background (dev mode)
make dev           # Start all services in foreground (dev mode)
make prod          # Build production image and start
make stop          # Stop all services
make stop-v        # Stop and remove all data volumes (destructive)
make logs          # Tail all logs
make logs-api      # Tail API logs only
make ps            # Show running containers
make shell-api     # Open shell inside API container
make shell-db      # Open psql in PostgreSQL container
make restart-api   # Restart only the API container
make clean         # Remove containers, volumes, and dangling images
```

---

## Spring Profiles

Set `SPRING_PROFILES_ACTIVE` in `.env`:

- `dev` — development mode, fixed OTP code, verbose logging
- `prod` — production mode, real OTP/email delivery, stricter settings

---

## Project Structure

```
fams-backend-project/
├── api-server/          # Java/Spring Boot application
│   ├── src/             # Application source code
│   └── Dockerfile       # Multi-stage build
├── database/
│   └── diagrams/        # ERD and schema docs
├── docker/              # Config files for Postgres, Redis, Nginx
├── docs/                # Architecture and API documentation
├── tests/               # Shell script test suites (curl-based)
├── docker-compose.yml   # Production compose
├── docker-compose.dev.yml # Dev overrides (source mount + Maven)
├── Makefile             # Developer shortcuts
└── .env.example         # Environment variable template
```

### API Modules

| Module | Base Path |
|---|---|
| Auth | `/api/v1/auth` |
| Employee | `/api/v1/employees` |
| RBAC | `/api/v1/roles`, `/api/v1/permissions` |
| Tenant | `/api/v1/tenants` |
| Subscription | `/api/v1/subscriptions` |

Swagger UI is available at: `http://localhost:8080/swagger-ui.html`

---

## Database Migrations

Flyway migrations run automatically when the API starts. Migration files are located at:

```
api-server/src/main/resources/db/migration/
```

No manual migration steps are needed.
