# FAMS Backend

Faculty and Alumni Management System — multi-service backend built with Spring Boot, PostgreSQL (PostGIS), Redis, and an optional Python AI service for Face ID and liveness detection.

## Stack Variants

| Variant | Compose file | Use when |
|---|---|---|
| **Java only** | `docker-compose.yml` | Working on core HR, attendance, check-in, RBAC — anything that does not involve Face ID |
| **Full stack** | `docker-compose.full.yml` | Working on or testing Face ID enrollment, face verification, or liveness detection |

> **Warning:** The Java API's Face ID endpoints (enrollment, face verification, liveness) call the Python AI service internally. If you start the Java-only stack, those specific endpoints will fail. All other API functionality works without the AI service.

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) 24+
- [Docker Compose](https://docs.docker.com/compose/install/) v2 (bundled with Docker Desktop)
- `make`

No local Java, Maven, or Python required — everything runs inside Docker.

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
| `GOOGLE_CLIENT_ID` | From Google Cloud Console → OAuth 2.0 credentials |
| `FCM_PROJECT_ID` | Firebase project ID — found in Firebase Console → Project Settings |
| `FCM_SERVICE_ACCOUNT_JSON` | Full service account JSON as a single-line string — download from Firebase Console → Project Settings → Service Accounts → Generate new private key, then minify and paste |

All other fields have safe defaults and can be left as-is for local development.

### 3. Start the backend and seed demo data

Choose the variant that matches your work:

**Java backend only (recommended for most development):**

```bash
make setup      # start + seed demo data (first time)
make dev-d      # subsequent starts
```

**Full stack including AI service (required for Face ID / liveness work):**

> **Note:** The first `full` build downloads PyTorch, TensorFlow, and face recognition models (~3–4 GB total). Subsequent builds are fast thanks to Docker layer caching.

```bash
make full-dev-d     # dev mode, background
# or
make full-d         # production image, background
```

> **Warning:** Face ID and liveness endpoints in the Java API require the full stack. Starting with `make dev`/`make setup` and then calling those endpoints will return errors.

The first Java start downloads Docker images and Maven dependencies — this may take a few minutes. Flyway migrations run automatically on startup.

### 4. Verify the system is live

```bash
curl http://localhost:8080/actuator/health
```

Expected response:

```json
{"status":"UP"}
```

---

## Default Accounts & Demo Data

After seeding, the following data is available:

**Platform admin**

| Field | Value |
|---|---|
| Email | `admin@fams.com` |
| Password | `Admin@1234` |

**Demo tenants**

| Tenant | Slug | Plan | Sites | Employees |
|---|---|---|---|---|
| Acme Corp | `acme-corp` | Pro | 3 | 12 (Alice Walker, Bob Smith, Charlie Jones…) |
| Beta Industries | `beta-industries` | Basic | 2 | 10 (Eve Taylor, Frank Wilson…) |
| Gamma Logistics | `gamma-logistics` | Enterprise | 3 | 12 (Oscar Martinez, Patricia Chen…) |

**Historical data (past 30 days)**

| Table | Count | Notes |
|---|---|---|
| `checkins` | ~672 | valid + pending_review; GPS coords, work minutes |
| `attendance_summaries` | ~672 | late flags, OT minutes |
| `scheduled_checks` | ~240 | responded + no_response statuses |
| `check_responses` | ~182 | GPS + outcome |
| `violations` | ~82 | no_response + location_fail; ~60% resolved |
| `face_profiles` | 34 | enrolled / pending / revoked / not_enrolled |
| `notifications` | 39 | 13 event types × 3 tenants |
| `audit_logs` | 30 | 10 action types × 3 tenants |

The seed script is fully idempotent — safe to run multiple times.

To reset and re-seed from scratch:

```bash
make stop-v   # wipe all data
make setup    # start fresh + seed
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

| Service | Host Port | Container | Stack |
|---|---|---|---|
| API Server | 8080 | `fams-api` | Both |
| PostgreSQL | 5433 | `fams-postgres` | Both |
| Redis | 6379 | `fams-redis` | Both |
| AI Service | *(internal only)* | `fams-ai` | Full stack only |

The AI service is intentionally not exposed to the host. It is reachable only from `fams-api` inside the Docker network at `http://fams-ai:5000`.

Ports can be changed via `DB_EXPOSE_PORT`, `REDIS_EXPOSE_PORT`, and `API_EXPOSE_PORT` in `.env`.

---

## Common Commands

**Java backend only:**

```bash
make setup         # First-time setup: start services + seed demo data
make dev-d         # Start in background (dev mode)
make dev           # Start in foreground (dev mode)
make seed          # Seed demo tenants and employees
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

**Full stack (Java + AI service):**

> **Warning:** Face ID and liveness endpoints require the full stack. Use `make dev`/`make setup` for all other development.

```bash
make full-d        # Start full stack in background (production image)
make full-dev-d    # Start full stack in background (dev mode, source mounted)
make full-dev      # Start full stack in foreground (dev mode)
make full-stop     # Stop full stack
make full-stop-v   # Stop full stack and remove volumes (destructive)
make logs-ai       # Tail AI service logs
make restart-ai    # Restart only the AI service container
make shell-ai      # Open bash inside AI service container
```

Run `make help` to see the full reference.

---

## Phone Authentication (Firebase)

Phone OTP login is implemented via **Firebase Phone Authentication**. The mobile/web client handles the full OTP flow with Firebase directly — the backend only receives and verifies the resulting Firebase ID Token.

### How the flow works

```
Mobile / Web App              Firebase                  FAMS Backend
──────────────────            ────────                  ────────────
signInWithPhoneNumber()  →→→  Sends OTP SMS
User enters code
confirmationResult()     →→→  Firebase validates code
getIdToken()             ←←←  Returns Firebase ID Token
POST /api/v1/auth/otp/verify ──────────────────────→  verifyIdToken()
{ firebaseIdToken }                                    lookup user by phone
                         ←←←←←←←←←←←←←←←←←←←←←←←←  return FAMS JWT pair
```

The backend never sends SMS itself — Firebase handles delivery entirely.

### Firebase project details

All values are in Firebase Console → Project Settings → General → Your apps.

| Field | Where to find it |
|---|---|
| Project ID | Project Settings → General → Project ID |
| Project Number | Project Settings → General → Project number |
| Web App ID | Project Settings → General → Your apps → App ID |
| Auth Domain | `<your-project-id>.firebaseapp.com` |
| Web API Key | Project Settings → General → Web API key |

> **Phone Authentication must be enabled** in Firebase Console → Authentication → Sign-in method → Phone.

### Backend env vars required

| Variable | Where to get it |
|---|---|
| `FCM_PROJECT_ID` | Firebase Console → Project Settings → General → Project ID |
| `FCM_SERVICE_ACCOUNT_JSON` | Firebase Console → Project Settings → Service Accounts → Generate new private key → minify JSON to single line |

The same Firebase Admin SDK used for push notifications (FCM) also verifies phone auth tokens — no additional credentials needed.

---

### Frontend integration guide

#### React (Web)

```bash
npm install firebase
```

```js
import { initializeApp } from 'firebase/app';
import { getAuth, signInWithPhoneNumber, RecaptchaVerifier } from 'firebase/auth';

const app = initializeApp({
  apiKey:            '<your-firebase-web-api-key>',
  authDomain:        '<your-project-id>.firebaseapp.com',
  projectId:         '<your-project-id>',
  storageBucket:     '<your-project-id>.firebasestorage.app',
  messagingSenderId: '<your-project-number>',
  appId:             '<your-web-app-id>',
});

const auth = getAuth(app);

// Step 1 — send OTP (invisible reCAPTCHA handled automatically)
const recaptcha = new RecaptchaVerifier(auth, 'recaptcha-container', { size: 'invisible' });
const confirmationResult = await signInWithPhoneNumber(auth, phoneNumber, recaptcha);

// Step 2 — verify code entered by user
const result = await confirmationResult.confirm(otpCode);
const firebaseIdToken = await result.user.getIdToken();

// Step 3 — exchange for FAMS JWT
const res = await fetch('/api/v1/auth/otp/verify', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ firebaseIdToken, deviceId: 'web' }),
});
const { data } = await res.json(); // data.accessToken, data.refreshToken
```

Add an invisible reCAPTCHA anchor anywhere in your page:

```html
<div id="recaptcha-container"></div>
```

#### React Native (Android & iOS)

```bash
npm install @react-native-firebase/app @react-native-firebase/auth
```

Follow the [React Native Firebase setup guide](https://rnfirebase.io/) to add `google-services.json` (Android) and `GoogleService-Info.plist` (iOS) — both are generated from the same Firebase project.

```js
import auth from '@react-native-firebase/auth';

// Step 1 — send OTP
const confirmation = await auth().signInWithPhoneNumber(phoneNumber);

// Step 2 — verify code entered by user
const result = await confirmation.confirm(otpCode);
const firebaseIdToken = await result.user.getIdToken();

// Step 3 — exchange for FAMS JWT
const res = await fetch(`${API_BASE_URL}/api/v1/auth/otp/verify`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ firebaseIdToken, deviceId }),
});
const { data } = await res.json(); // data.accessToken, data.refreshToken
```

React Native Firebase handles reCAPTCHA / SafetyNet / App Attest automatically on each platform — no extra configuration needed.

---

### Testing

#### Option A — Test phone numbers (recommended for development, no SMS quota used)

Register a fake phone number with a fixed OTP code in Firebase Console:

> Firebase Console → Authentication → Sign-in method → Phone → **Test phone numbers** → Add

Use any E.164 number (e.g. `+84900000000`) and any 6-digit code (e.g. `456789`). Firebase will accept this number without sending a real SMS and without reCAPTCHA.

Run the backend manual test:

```bash
FIREBASE_API_KEY=<your-web-api-key> BASE_URL=http://localhost:8080 bash tests/auth/test_otp_login.sh
```

The script prompts for phone number and OTP code interactively, then verifies the full flow against the running backend.

#### Option B — Real phone + real SMS (end-to-end)

Use your actual React or React Native app to complete the Firebase Phone Auth flow. Once you have the Firebase ID Token, test the backend directly:

```bash
FIREBASE_ID_TOKEN=<token> BASE_URL=http://localhost:8080 bash tests/auth/test_otp_login.sh
```

The script skips all Firebase steps and sends the token straight to the backend.

> **Quota:** Firebase free tier allows 10 real SMS per day. Use test phone numbers for repeated backend testing and save the real SMS quota for full end-to-end app testing.

---

## Spring Profiles

Set `SPRING_PROFILES_ACTIVE` in `.env`:

- `dev` — development mode, fixed OTP code, verbose logging
- `prod` — production mode, real OTP/email delivery, stricter settings

---

## Project Structure

```
fams-backend-project/
├── api-server/              # Java/Spring Boot application
│   ├── src/                 # Application source code
│   └── Dockerfile           # Multi-stage build
├── ai-service/              # Python FastAPI AI service (Face ID + liveness)
│   ├── app/                 # FastAPI application
│   ├── storage/             # Host-mounted face image store (gitignored)
│   ├── Dockerfile           # Includes pre-baked model weights
│   └── requirements.txt
├── database/
│   └── diagrams/            # ERD and schema docs
├── docker/                  # Config files for Postgres, Redis, Nginx
├── docs/                    # Architecture and API documentation
├── tests/                   # Shell script test suites (curl-based)
├── docker-compose.yml       # Java backend only (postgres + redis + api)
├── docker-compose.full.yml  # Full stack (adds AI service)
├── docker-compose.dev.yml   # Dev overrides (source mount + Maven); works with both compose files
├── Makefile                 # Developer shortcuts
└── .env.example             # Environment variable template
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
