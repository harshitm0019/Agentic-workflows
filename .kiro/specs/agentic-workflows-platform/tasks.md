# Implementation Plan:

## Overview

Build the Agentic Workflows Platform MVP with a Java/Spring Boot backend, Next.js dashboard, Supabase database, Gemini AI integration, and GitHub webhook triggers. The platform orchestrates 3 AI agents (code review, log analysis, change suggestion) through sequential workflows with human-in-the-loop approval.

## Tasks

- [x] 1. Project Scaffolding & Spring Boot Setup: Initialize Maven project with Java 21 and Spring Boot 3.x at `backend/`. Add dependencies: spring-boot-starter-web, spring-boot-starter-data-jpa, spring-retry, spring-boot-starter-webflux, postgresql driver, jackson-databind, lombok, hypersistence-utils. Create `AgenticWorkflowsApplication.java`, `application.yml` with server port 8080 and datasource placeholders, `.env.example` with all environment variables, and `backend/Dockerfile` (multi-stage Maven build). Project must compile and start without errors.
  - **Requirements:** 1, 9
  - **Dependencies:** None

- [x] 2. Database Schema & JPA Entities: Create SQL migration for 4 tables (workflow_runs, workflow_steps, review_items, gemini_usage) with proper UUIDs, foreign keys, indexes, and CHECK constraints. Create JPA entities (WorkflowRun, WorkflowStepRecord, ReviewItem, GeminiUsage) with JSONB column mapping. Create Spring Data repositories with custom queries. Configure `spring.jpa.hibernate.ddl-auto=validate`. Apply migration to Supabase and verify app starts successfully.
  - **Requirements:** 5, 7, 8
  - **Dependencies:** 1

- [x] 3. Gemini Service with Rate Limiting: Implement `GeminiService` as Spring `@Service` that calls Gemini `generateContent` REST endpoint via WebClient. Track request count and token usage in `gemini_usage` table per day (reset at UTC midnight). Log warning at 80% of daily limit. Throw `QuotaExhaustedException` at 100%. Use `@Retryable` for 429 errors (3 attempts, exponential backoff 1s/2s/4s). Create GeminiRequest/GeminiResponse DTOs. Write unit tests with mocked WebClient.
  - **Requirements:** 2, 3, 8
  - **Dependencies:** 2

- [x] 4. GitHub Service: Implement `GitHubService` as Spring `@Service` with WebClient. Methods: `getPRDiff(repoFullName, prNumber)` fetches diff via GitHub API, `getWorkflowLogs(repoFullName, runId)` fetches run logs (truncate to 50K chars), `pushCommit(repoFullName, branch, filePath, content, message)` creates/updates files. Authenticate with Bearer token from env. Handle 401/403/404 with descriptive exceptions. Write unit tests with WireMock.
  - **Requirements:** 6, 4
  - **Dependencies:** 1

- [x] 5. Agent Configuration & Base Agent: Create `agents.yaml` in `src/main/resources/` with definitions for code-review, log-analysis, change-suggestion (name, description, capabilities, model, temperature, maxTokens, systemPrompt). Create `AgentsConfig` class with `@ConfigurationProperties`. Validate at startup, log errors for invalid agents, skip them. Create abstract `BaseAgent` class with `execute(AgentInput)` and `callGemini(prompt)`. Create `AgentInput` and `AgentOutput` records.
  - **Requirements:** 1
  - **Dependencies:** 3

- [x] 6. Code Review Agent: Implement `CodeReviewAgent` extending `BaseAgent`. Validate input diff is not empty and ≤2000 lines (reject with error if exceeded). Construct prompt for Gemini to return JSON findings array (filePath, lineNumber, severity, message). Parse response into structured findings. Return empty findings with summary if no issues. Handle malformed Gemini responses gracefully. Write unit tests for all cases.
  - **Requirements:** 2
  - **Dependencies:** 5

- [x] 7. Log Analysis Agent: Implement `LogAnalysisAgent` extending `BaseAgent`. Truncate logs to last 20K chars if input >50K chars. Reject empty or <10 char input. Construct prompt for Gemini to return JSON report (errorType, rootCause, affectedComponents, suggestedFix, confidenceScore). Return errorType "none" if no failure detected. Write unit tests for all cases.
  - **Requirements:** 3
  - **Dependencies:** 5

- [x] 8. Change Suggestion Agent: Implement `ChangeSuggestionAgent` extending `BaseAgent`. Accept findings or failure report as input. Construct prompt for Gemini to generate unified diff patch. Set `requiresReview=true` in output. Return success=false with explanation if no fix possible. Write unit tests.
  - **Requirements:** 4
  - **Dependencies:** 5

- [x] 9. Workflow Loader & Template Resolver: Implement `WorkflowLoader` to parse YAML files from `src/main/resources/workflows/` into `WorkflowDefinition` records. Validate no undefined agent references, no duplicate step names. Create `TemplateResolver` to resolve `{{trigger.<field>}}` and `{{steps.<stepName>.output.<field>}}` references. Create `pr-review.yaml` and `failure-analysis.yaml` workflow definitions. Write unit tests for parsing and template resolution.
  - **Requirements:** 5
  - **Dependencies:** 5

- [x] 10. Workflow Engine: Implement `WorkflowEngine` as `@Service` with `@Async` execution. `executeWorkflow(def, triggerPayload)` creates WorkflowRun, iterates steps sequentially. Each step: resolve input, run agent, store WorkflowStepRecord. On `requiresReview`: create ReviewItem, set status to `paused_for_review`. On error+halt: set run to failed. On error+skip: continue. Enforce 5-min timeout per step via CompletableFuture. Implement `resumeAfterReview(runId, approved, reason)`. Wrap state transitions in `@Transactional`. Write integration tests with H2.
  - **Requirements:** 5, 4
  - **Dependencies:** 6, 7, 8, 9

- [x] 11. Webhook Controller: Implement `WebhookController` with `POST /webhook/github`. Validate HMAC-SHA256 signature (return 401 on failure). Handle `pull_request` (opened/synchronize): fetch diff, trigger pr-review workflow. Handle `workflow_run` (failure): fetch logs, trigger failure-analysis workflow. Deduplicate via delivery ID unique constraint. Return 200 on success. Write MockMvc tests.
  - **Requirements:** 6, 10
  - **Dependencies:** 4, 10

- [x] 12. Review & Workflow REST Controllers: Implement `WorkflowController` (GET /api/workflows, GET /api/workflows/{id}), `ReviewController` (GET /api/reviews/pending, POST /api/reviews/{id}/approve, POST /api/reviews/{id}/reject with reason), `UsageController` (GET /api/usage). Configure CORS for localhost:3000. Return proper JSON responses and HTTP status codes. Write MockMvc tests.
  - **Requirements:** 7, 8
  - **Dependencies:** 10

- [x] 13. Next.js Dashboard Setup: Initialize Next.js 14 project at `dashboard/` with App Router, TypeScript, Tailwind CSS, `@supabase/supabase-js`. Create layout with navigation (Workflows, Reviews, Usage). Create API utility (`lib/api.ts`) with typed functions for all backend endpoints. Set up Supabase realtime subscription (`lib/supabase.ts`) for workflow_runs and review_items. Create `dashboard/Dockerfile`. App starts on port 3000 with empty state.
  - **Requirements:** 7, 9
  - **Dependencies:** None

- [x] 14. Dashboard - Workflow Pages: Build workflow list page (/) showing runs as cards with name, status badge (color-coded), trigger event, started time. Clicking navigates to detail page (/workflows/[id]) showing metadata, step progress with status indicators, expandable step outputs. Real-time updates via Supabase subscription. Handle loading, error, and empty states.
  - **Requirements:** 7
  - **Dependencies:** 13

- [x] 15. Dashboard - Review Queue & Diff Viewer: Build review page (/reviews) showing pending items ordered by creation time. Each item shows agent name, workflow name, review type, content. DiffViewer component renders unified diffs with syntax highlighting. Approve button → API call → show commit SHA. Reject button → text input for reason → API call. Items disappear after decision via realtime. Handle loading and errors.
  - **Requirements:** 7, 4
  - **Dependencies:** 13

- [x] 16. Dashboard - Usage Stats: Build usage section showing requests today/limit, tokens today/limit. Visual progress bar (green <50%, yellow 50-80%, red >80%). Warning banner at >80%. Data from GET /api/usage. Auto-refresh every 30 seconds.
  - **Requirements:** 8
  - **Dependencies:** 13

- [x] 17. Docker Compose & Local Deployment: Create `docker-compose.yml` with backend (port 8080) and dashboard (port 3000) services. Both read from `.env`. Add backend healthcheck (GET /actuator/health). Ensure `docker-compose up` starts both services healthy within 60 seconds. Add `.env` to `.gitignore`.
  - **Requirements:** 9
  - **Dependencies:** 1, 13

- [x] 18. Sample GitHub Action & README: Create `.github/workflows/notify-platform.yml` triggering on pull_request and workflow_run events, sending webhook to platform with proper headers and signature. Update `README.md` with: project overview, architecture diagram, prerequisites (Java 21, Node 18+, Docker), setup steps, env config, first run guide, how to expose localhost (ngrok/smee.io).
  - **Requirements:** 6, 9
  - **Dependencies:** 11, 17

- [x] 19. Integration Testing & End-to-End Verification: Write integration tests: webhook → workflow run → steps execute → review item created; review approved → workflow resumes → commit pushed (mocked); review rejected → workflow failed; quota exhausted → workflow paused; invalid signature → 401. Use H2 + WireMock. All tests run via `mvn test` with no external deps. Deterministic, CI-friendly.
  - **Requirements:** All
  - **Dependencies:** 11, 12, 10

## Task Dependency Graph

```json
{
  "waves": [
    [1, 13],
    [2, 4],
    [3],
    [5],
    [6, 7, 8, 9],
    [10],
    [11, 12, 14, 15, 16],
    [17],
    [18, 19]
  ]
}
```

**Wave 1:** Project scaffolding (backend + dashboard in parallel)
**Wave 2:** Database schema + GitHub service (independent)
**Wave 3:** Gemini service (needs DB)
**Wave 4:** Agent config + base class (needs Gemini service)
**Wave 5:** All 3 agents + workflow loader (need base agent)
**Wave 6:** Workflow engine (needs all agents + loader)
**Wave 7:** Controllers + dashboard pages (need engine)
**Wave 8:** Docker Compose (needs both backend + dashboard)
**Wave 9:** GitHub Action, README, integration tests (needs everything)

## Notes

- Tasks 1-12 (backend) and 13-16 (dashboard) can be developed in parallel by splitting work.
- Task 17 (Docker Compose) is the integration point where both tracks merge.
- The backend can be tested independently using curl/Postman before the dashboard is ready.
- Supabase schema (Task 2) should be applied to your hosted instance before other DB-dependent tasks.
