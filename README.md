# Agentic Workflows Platform

A lightweight platform that orchestrates specialized AI agents to automate software development tasks. GitHub events (pull requests, pipeline failures) trigger sequential workflows where agents analyze code, diagnose failures, and suggest fixes — with human approval before any changes are pushed.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Local Machine                             │
│                                                                  │
│  ┌──────────────┐     ┌──────────────────┐    ┌─────────────┐  │
│  │   GitHub     │────▶│  Backend API     │───▶│  Supabase   │  │
│  │  (webhook)   │     │ (Spring Boot)    │    │  (hosted)   │  │
│  └──────────────┘     └────────┬─────────┘    └─────────────┘  │
│                                │                                 │
│                     ┌──────────┼──────────┐                     │
│                     │          │          │                      │
│                     ▼          ▼          ▼                      │
│              ┌──────────┐ ┌────────┐ ┌──────────┐              │
│              │Code Review│ │  Log   │ │ Change   │              │
│              │  Agent    │ │Analysis│ │Suggestion│              │
│              └──────────┘ │ Agent  │ │  Agent   │              │
│                           └────────┘ └──────────┘              │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              Dashboard (Next.js on :3000)                 │   │
│  │   - Workflow status    - Review queue    - Usage stats    │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

**Core Agents:**
- **Code Review Agent** — Analyzes PR diffs and returns findings (file, line, severity, message)
- **Log Analysis Agent** — Diagnoses pipeline failures from logs (root cause, affected components, suggested fix)
- **Change Suggestion Agent** — Generates unified diff patches for human review before pushing

**Data Flow:**
1. GitHub event → webhook to backend (port 8080)
2. Backend validates HMAC signature, fetches diff/logs via GitHub API
3. Workflow engine runs agents sequentially, stores results in Supabase
4. Dashboard (port 3000) shows pending reviews via Supabase realtime
5. Human approves → patch pushed to repo; rejects → discarded

## Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| Java | 21+ | Backend runtime |
| Node.js | 18+ | Dashboard build |
| Docker | 20+ | Container runtime |
| Docker Compose | 2.x | Orchestration |
| Supabase account | Free tier | Hosted Postgres database |
| GitHub PAT | — | Personal access token with `repo` scope |
| Gemini API key | Free tier | Google AI Studio |

## Setup

### 1. Clone the repository

```bash
git clone https://github.com/your-org/agentic-workflows.git
cd agentic-workflows
```

### 2. Configure environment variables

```bash
cp .env.example .env
```

Edit `.env` with your values:

```bash
# GitHub Integration
GITHUB_TOKEN=ghp_your_personal_access_token
GITHUB_WEBHOOK_SECRET=a-random-secret-string

# Gemini AI (get from https://aistudio.google.com/apikey)
GEMINI_API_KEY=AIza_your_key
GEMINI_DAILY_LIMIT=1500

# Supabase - Backend (JDBC connection)
SPRING_DATASOURCE_URL=jdbc:postgresql://db.your-project.supabase.co:5432/postgres
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=your-db-password
SPRING_DATASOURCE_DRIVER=org.postgresql.Driver
SPRING_JPA_DDL_AUTO=validate
SPRING_JPA_DIALECT=org.hibernate.dialect.PostgreSQLDialect
FLYWAY_ENABLED=true
SPRING_PROFILES_ACTIVE=postgres

# Supabase - Dashboard (JS client for realtime)
NEXT_PUBLIC_SUPABASE_URL=https://your-project.supabase.co
NEXT_PUBLIC_SUPABASE_ANON_KEY=eyJh_your_anon_key
```

### 3. Apply database migration

Run the Flyway migration against your Supabase instance. The migration file is at `backend/src/main/resources/db/migration/V1__create_schema.sql`.

You can apply it directly via the Supabase SQL Editor, or let the backend apply it on first start (Flyway is enabled by default).

### 4. Start the platform

```bash
docker-compose up --build
```

This starts:
- **Backend API** on `http://localhost:8080`
- **Dashboard** on `http://localhost:3000`

Both services should be healthy within 60 seconds. The backend exposes a health endpoint at `/actuator/health`.

## First Run Guide

1. Start the platform with `docker-compose up`
2. Open the dashboard at `http://localhost:3000`
3. Verify the backend is running: `curl http://localhost:8080/actuator/health`
4. Check Gemini usage at `http://localhost:3000` (Usage section)
5. Trigger a test webhook (see below) or open a PR on a connected repo

### Triggering a test webhook manually

```bash
# Generate a signature for your payload
PAYLOAD='{"action":"opened","number":1,"pull_request":{"title":"Test PR"},"repository":{"full_name":"your-org/your-repo"}}'
SECRET="your-webhook-secret"
SIGNATURE=$(echo -n "$PAYLOAD" | openssl dgst -sha256 -hmac "$SECRET" | awk '{print "sha256="$2}')

curl -X POST http://localhost:8080/webhook/github \
  -H "Content-Type: application/json" \
  -H "X-GitHub-Event: pull_request" \
  -H "X-GitHub-Delivery: test-delivery-001" \
  -H "X-Hub-Signature-256: $SIGNATURE" \
  -d "$PAYLOAD"
```

## Exposing Localhost for GitHub Webhooks

GitHub needs to reach your local machine to deliver webhooks. Use one of these tools to create a public tunnel:

### Option A: ngrok

```bash
# Install ngrok (https://ngrok.com/download)
ngrok http 8080
```

ngrok gives you a public URL like `https://abc123.ngrok-free.app`. Use this as your webhook URL:

```
https://abc123.ngrok-free.app/webhook/github
```

### Option B: smee.io

```bash
# Install smee client
npm install -g smee-client

# Create a channel at https://smee.io/ and copy the URL
smee --url https://smee.io/your-channel --target http://localhost:8080/webhook/github
```

smee.io proxies GitHub webhook deliveries to your local machine. Set the smee.io URL as the webhook URL in your GitHub repository settings.

### Configuring the webhook in GitHub

1. Go to your repository → Settings → Webhooks → Add webhook
2. **Payload URL:** Your ngrok/smee URL + `/webhook/github`
3. **Content type:** `application/json`
4. **Secret:** Same value as `GITHUB_WEBHOOK_SECRET` in your `.env`
5. **Events:** Select "Pull requests" and "Workflow runs"

## Configuring the Sample GitHub Action

This repository includes a GitHub Action at `.github/workflows/notify-platform.yml` that forwards PR and workflow failure events to your platform. To use it in another repository:

1. Copy `.github/workflows/notify-platform.yml` to the target repository
2. Add these secrets to the target repository (Settings → Secrets → Actions):
   - `PLATFORM_WEBHOOK_URL`: Your platform's webhook endpoint (e.g., `https://abc123.ngrok-free.app/webhook/github`)
   - `PLATFORM_WEBHOOK_SECRET`: Same value as `GITHUB_WEBHOOK_SECRET` in your `.env`
3. The action triggers automatically on PR open/sync and workflow failures

This approach works as an alternative to configuring repository webhooks directly — useful when you don't have admin access to the repository settings.

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/webhook/github` | Receives GitHub webhook events |
| GET | `/api/workflows` | List all workflow runs |
| GET | `/api/workflows/{id}` | Get workflow run with steps |
| GET | `/api/reviews/pending` | List pending review items |
| POST | `/api/reviews/{id}/approve` | Approve a review item |
| POST | `/api/reviews/{id}/reject` | Reject a review item |
| GET | `/api/usage` | Current Gemini usage stats |

## Project Structure

```
agentic-workflows/
├── backend/                    # Spring Boot API (Java 21)
│   ├── src/main/java/com/agentic/
│   │   ├── agent/             # AI agents (CodeReview, LogAnalysis, ChangeSuggestion)
│   │   ├── config/            # Spring configuration
│   │   ├── controller/        # REST controllers
│   │   ├── engine/            # Workflow engine & loader
│   │   ├── model/             # JPA entities
│   │   ├── repository/        # Spring Data repositories
│   │   └── service/           # Gemini & GitHub services
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── agents.yaml        # Agent definitions
│   │   ├── workflows/         # Workflow YAML definitions
│   │   └── db/migration/      # Flyway SQL migrations
│   ├── Dockerfile
│   └── pom.xml
├── dashboard/                  # Next.js 14 frontend
│   ├── src/app/               # App Router pages
│   ├── src/components/        # React components
│   ├── src/lib/               # API client & Supabase setup
│   ├── Dockerfile
│   └── package.json
├── .github/workflows/
│   └── notify-platform.yml    # Sample GitHub Action
├── docker-compose.yml
├── .env.example
└── README.md
```

## Tech Stack

- **Backend:** Java 21, Spring Boot 3.x, Spring Data JPA, Spring Retry, WebClient
- **Dashboard:** Next.js 14, React, TypeScript, Tailwind CSS, Supabase JS client
- **Database:** Supabase (hosted PostgreSQL) with Flyway migrations
- **AI:** Google Gemini API (free tier)
- **Containers:** Docker, Docker Compose
- **CI Trigger:** GitHub Webhooks + GitHub Actions

## License

MIT
