# Technical Design Document

## Overview

The Agentic Workflows Platform MVP is a Java/Spring Boot backend with a Next.js dashboard, backed by Supabase (hosted Postgres) for persistence, triggered by GitHub webhooks, and using Gemini free-tier for AI inference. All services run locally via Docker Compose.

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

## Components and Interfaces

### Backend API (Spring Boot - Port 8080)
- **Webhook Controller**: Receives GitHub events, validates HMAC signatures, dispatches to workflow engine
- **Workflow Engine**: Loads YAML definitions, executes steps sequentially, manages state in Supabase via JPA
- **Agent Runner**: Instantiates agents via Spring DI, passes input, collects output
- **Gemini Service**: Rate-limited LLM client with retry logic (Spring Retry)
- **GitHub Service**: Fetches diffs, logs, pushes commits (WebClient)
- **REST Controllers**: Expose workflow and review endpoints for the Dashboard

### Dashboard (Next.js - Port 3000)
- **Workflow List Page**: Shows all runs with status
- **Workflow Detail Page**: Shows steps and their outputs
- **Review Queue Page**: Lists pending approvals with approve/reject buttons
- **Usage Stats Widget**: Displays Gemini consumption

### Agents (Spring-managed beans)
- **BaseAgent**: Abstract class with Gemini integration
- **CodeReviewAgent**: Accepts diff → returns findings JSON
- **LogAnalysisAgent**: Accepts logs → returns failure report JSON
- **ChangeSuggestionAgent**: Accepts findings/report → returns unified diff patch

### External Services
- **Supabase (hosted)**: Postgres database + realtime subscriptions (dashboard uses JS client, backend connects via JDBC/JPA)
- **Gemini API**: LLM inference (free tier)
- **GitHub API**: Repository data access + commit pushing

### Interface: Backend ↔ Dashboard
Dashboard calls Backend REST API for actions (approve/reject). Dashboard subscribes to Supabase realtime for live status updates (no polling needed).

### Interface: Backend ↔ GitHub
GitHub sends webhook POST to Backend. Backend calls GitHub REST API via WebClient for diffs/logs/pushes.

### Interface: Backend ↔ Gemini
Backend calls Gemini generative API via WebClient. Rate limiting and retry handled by GeminiService with Spring Retry.

### Interface: Backend ↔ Supabase
Backend connects to Supabase Postgres directly via JDBC (Spring Data JPA). No Supabase client SDK needed — it's just Postgres.

## Data Models

### AgentConfig (from agents.yaml loaded via @ConfigurationProperties)
```java
public record AgentConfig(
    String name,
    String description,
    List<String> capabilities,
    String model,           // e.g., "gemini-1.5-flash"
    double temperature,     // 0.0-1.0
    int maxTokens,
    String systemPrompt
) {}
```

### WorkflowDefinition (parsed from YAML)
```java
public record WorkflowDefinition(
    String name,
    String trigger,         // "pull_request" or "workflow_run_failure"
    String description,
    List<WorkflowStep> steps
) {}

public record WorkflowStep(
    String name,
    String agent,
    Map<String, String> input,  // Template references like "{{trigger.diff}}"
    String onError,             // "halt" or "skip"
    boolean requiresReview
) {}
```

### AgentInput / AgentOutput
```java
public record AgentInput(
    String type,
    Map<String, Object> data,
    Map<String, Object> previousStepOutput
) {}

public record AgentOutput(
    boolean success,
    Map<String, Object> data,
    boolean requiresReview,
    String error
) {}
```

### JPA Entities

```java
@Entity
@Table(name = "workflow_runs")
public class WorkflowRun {
    @Id @GeneratedValue UUID id;
    String workflowName;
    String triggerEvent;
    @Type(JsonType.class) Map<String, Object> triggerPayload;
    String status;  // running, paused_for_review, completed, failed
    int currentStep;
    Instant startedAt;
    Instant completedAt;
    String error;
}

@Entity
@Table(name = "workflow_steps")
public class WorkflowStepRecord {
    @Id @GeneratedValue UUID id;
    UUID workflowRunId;
    int stepIndex;
    String agentName;
    @Type(JsonType.class) Map<String, Object> input;
    @Type(JsonType.class) Map<String, Object> output;
    String status;  // completed, failed, timed_out, skipped
    Instant startedAt;
    Instant completedAt;
    String error;
}

@Entity
@Table(name = "review_items")
public class ReviewItem {
    @Id @GeneratedValue UUID id;
    UUID workflowRunId;
    UUID workflowStepId;
    String agentName;
    String reviewType;  // approve_patch, review_analysis
    @Type(JsonType.class) Map<String, Object> payload;
    String status;      // pending, approved, rejected
    String decisionReason;
    Instant createdAt;
    Instant decidedAt;
}

@Entity
@Table(name = "gemini_usage")
public class GeminiUsage {
    @Id @GeneratedValue UUID id;
    LocalDate date;
    int requestCount;
    int totalTokens;
    Instant updatedAt;
}
```

## Tech Stack

| Layer | Technology | Reason |
|-------|-----------|--------|
| Backend API | Java 21 + Spring Boot 3.x + Spring Data JPA | Mature, strongly typed, good for enterprise patterns |
| HTTP Client | Spring WebClient (reactive) | Non-blocking calls to Gemini/GitHub APIs |
| Retry | Spring Retry | Declarative retry with exponential backoff |
| YAML Parsing | SnakeYAML (bundled with Spring) | Parse workflow definitions and agent configs |
| Dashboard | Next.js 14 + React + Tailwind CSS | Quick UI with SSR, Supabase realtime integration |
| Database | Supabase (hosted Postgres) via JDBC | Free tier, backend connects directly as Postgres, dashboard uses realtime subscriptions |
| AI/LLM | Google Gemini API (free tier) | Zero cost, good code understanding |
| Build | Maven or Gradle | Standard Java build tool |
| Container | Docker + Docker Compose | Single-command local setup |
| CI Trigger | GitHub Webhooks | Direct integration, no polling |

## Project Structure

```
agentic-workflows/
├── backend/                          # Spring Boot application
│   ├── src/main/java/com/agentic/
│   │   ├── AgenticWorkflowsApplication.java
│   │   ├── config/
│   │   │   ├── AgentsConfig.java        # @ConfigurationProperties for agents.yaml
│   │   │   ├── WebClientConfig.java     # WebClient beans for Gemini/GitHub
│   │   │   └── AppConfig.java           # General app config
│   │   ├── agent/
│   │   │   ├── BaseAgent.java           # Abstract agent class
│   │   │   ├── CodeReviewAgent.java
│   │   │   ├── LogAnalysisAgent.java
│   │   │   └── ChangeSuggestionAgent.java
│   │   ├── engine/
│   │   │   ├── WorkflowEngine.java      # Sequential step executor
│   │   │   ├── WorkflowLoader.java      # YAML parser + validator
│   │   │   └── TemplateResolver.java    # Resolves {{trigger.x}} references
│   │   ├── service/
│   │   │   ├── GeminiService.java       # Gemini API client + rate limiting
│   │   │   └── GitHubService.java       # GitHub API client
│   │   ├── controller/
│   │   │   ├── WebhookController.java   # POST /webhook/github
│   │   │   ├── WorkflowController.java  # GET /api/workflows, GET /api/workflows/{id}
│   │   │   ├── ReviewController.java    # POST /api/reviews/{id}/approve|reject
│   │   │   └── UsageController.java     # GET /api/usage
│   │   ├── model/
│   │   │   ├── WorkflowRun.java         # JPA entity
│   │   │   ├── WorkflowStepRecord.java  # JPA entity
│   │   │   ├── ReviewItem.java          # JPA entity
│   │   │   └── GeminiUsage.java         # JPA entity
│   │   ├── repository/
│   │   │   ├── WorkflowRunRepository.java
│   │   │   ├── WorkflowStepRepository.java
│   │   │   ├── ReviewItemRepository.java
│   │   │   └── GeminiUsageRepository.java
│   │   └── dto/
│   │       ├── AgentInput.java
│   │       ├── AgentOutput.java
│   │       └── ReviewDecisionRequest.java
│   ├── src/main/resources/
│   │   ├── application.yml              # Spring config (DB, server port, etc.)
│   │   ├── agents.yaml                  # Agent definitions
│   │   └── workflows/
│   │       ├── pr-review.yaml
│   │       └── failure-analysis.yaml
│   ├── pom.xml                          # Maven build
│   └── Dockerfile
├── dashboard/                           # Next.js frontend
│   ├── src/
│   │   ├── app/
│   │   │   ├── page.tsx                 # Workflow list (home)
│   │   │   ├── workflows/[id]/page.tsx  # Workflow detail
│   │   │   └── reviews/page.tsx         # Pending reviews
│   │   ├── components/
│   │   │   ├── WorkflowCard.tsx
│   │   │   ├── ReviewItem.tsx
│   │   │   ├── DiffViewer.tsx
│   │   │   └── UsageStats.tsx
│   │   └── lib/
│   │       ├── supabase.ts              # Supabase client (realtime)
│   │       └── api.ts                   # Backend API calls
│   ├── package.json
│   ├── next.config.js
│   └── Dockerfile
├── docker-compose.yml
├── .env.example
├── .github/
│   └── workflows/
│       └── notify-platform.yml          # Sample GitHub Action
└── README.md
```

## Database Schema (Supabase)

### Tables

#### `workflow_runs`
Stores each execution of a workflow.

| Column | Type | Description |
|--------|------|-------------|
| id | uuid (PK) | Unique run identifier |
| workflow_name | text | Name of the workflow definition |
| trigger_event | text | What triggered it (pr_opened, pipeline_failed) |
| trigger_payload | jsonb | GitHub event payload (PR number, repo, etc.) |
| status | text | running, paused_for_review, completed, failed |
| current_step | integer | Index of the currently executing step |
| started_at | timestamptz | When execution began |
| completed_at | timestamptz | When execution finished (null if in progress) |
| error | text | Error message if failed |

#### `workflow_steps`
Stores output from each completed step in a run.

| Column | Type | Description |
|--------|------|-------------|
| id | uuid (PK) | Step record ID |
| workflow_run_id | uuid (FK) | Reference to workflow_runs |
| step_index | integer | Position in the workflow |
| agent_name | text | Which agent ran this step |
| input | jsonb | Input passed to the agent |
| output | jsonb | Agent's output |
| status | text | completed, failed, timed_out, skipped |
| started_at | timestamptz | Step start time |
| completed_at | timestamptz | Step end time |
| error | text | Error details if failed |

#### `review_items`
Queue of items waiting for human approval.

| Column | Type | Description |
|--------|------|-------------|
| id | uuid (PK) | Review item ID |
| workflow_run_id | uuid (FK) | Which workflow this belongs to |
| workflow_step_id | uuid (FK) | Which step produced this |
| agent_name | text | Agent that generated the output |
| review_type | text | approve_patch, review_analysis |
| payload | jsonb | The content to review (diff, report) |
| status | text | pending, approved, rejected |
| decision_reason | text | Reason for rejection (null if approved) |
| created_at | timestamptz | When the review was queued |
| decided_at | timestamptz | When human made a decision |

#### `gemini_usage`
Tracks daily API consumption.

| Column | Type | Description |
|--------|------|-------------|
| id | uuid (PK) | Record ID |
| date | date | Calendar day (UTC) |
| request_count | integer | Number of API calls made |
| total_tokens | integer | Total tokens consumed |
| updated_at | timestamptz | Last update time |

## Key Component Designs

### 1. Agent Base Class

```java
// backend/src/main/java/com/agentic/agent/BaseAgent.java
public abstract class BaseAgent {

    protected final String name;
    protected final AgentConfig config;
    protected final GeminiService geminiService;

    protected BaseAgent(String name, AgentConfig config, GeminiService geminiService) {
        this.name = name;
        this.config = config;
        this.geminiService = geminiService;
    }

    public abstract AgentOutput execute(AgentInput input);

    protected String callGemini(String userPrompt) {
        return geminiService.generate(GeminiRequest.builder()
            .model(config.model())
            .systemPrompt(config.systemPrompt())
            .userPrompt(userPrompt)
            .temperature(config.temperature())
            .maxTokens(config.maxTokens())
            .build());
    }
}
```

### 2. Workflow Engine (Sequential)

```java
// backend/src/main/java/com/agentic/engine/WorkflowEngine.java
@Service
public class WorkflowEngine {

    private final WorkflowRunRepository runRepo;
    private final WorkflowStepRepository stepRepo;
    private final ReviewItemRepository reviewRepo;
    private final Map<String, BaseAgent> agents;
    private final TemplateResolver templateResolver;

    @Async
    public void executeWorkflow(WorkflowDefinition def, Map<String, Object> triggerPayload) {
        WorkflowRun run = createRun(def.name(), triggerPayload);

        for (int i = 0; i < def.steps().size(); i++) {
            WorkflowStep step = def.steps().get(i);
            Map<String, Object> previousOutput = i > 0 
                ? getStepOutput(run.getId(), i - 1) 
                : triggerPayload;

            try {
                Map<String, Object> resolvedInput = templateResolver.resolve(step.input(), triggerPayload, previousOutput);
                AgentOutput result = runStep(run.getId(), i, step, resolvedInput);

                if (result.requiresReview()) {
                    pauseForReview(run, i, step.agent(), result);
                    return; // Resumes when human decides
                }
            } catch (Exception e) {
                handleStepError(run, i, step, e);
                if ("halt".equals(step.onError())) return;
            }
        }

        completeRun(run);
    }

    public void resumeAfterReview(UUID workflowRunId, boolean approved, String reason) {
        // Load run, find paused step, continue from next step or push patch
    }
}
```

### 3. Workflow YAML Format

```yaml
# backend/src/main/resources/workflows/pr-review.yaml
name: pr-review
trigger: pull_request
description: Reviews PR code and suggests fixes

steps:
  - name: review-code
    agent: code-review
    input:
      diff: "{{trigger.diff}}"
    onError: halt

  - name: suggest-fixes
    agent: change-suggestion
    input:
      findings: "{{steps.review-code.output.findings}}"
    onError: halt
    requiresReview: true
```

```yaml
# backend/src/main/resources/workflows/failure-analysis.yaml
name: failure-analysis
trigger: workflow_run_failure
description: Analyzes pipeline failure and suggests fixes

steps:
  - name: analyze-logs
    agent: log-analysis
    input:
      logs: "{{trigger.logs}}"
    onError: halt

  - name: suggest-fix
    agent: change-suggestion
    input:
      report: "{{steps.analyze-logs.output}}"
    onError: halt
    requiresReview: true
```

### 4. Gemini Service with Rate Limiting

```java
// backend/src/main/java/com/agentic/service/GeminiService.java
@Service
public class GeminiService {

    private final WebClient webClient;
    private final GeminiUsageRepository usageRepo;
    
    @Value("${gemini.daily-limit}")
    private int dailyLimit;

    @Retryable(value = RateLimitException.class, maxAttempts = 3,
               backoff = @Backoff(delay = 1000, multiplier = 2))
    public String generate(GeminiRequest request) {
        GeminiUsage usage = getTodayUsage();

        if (usage.getRequestCount() >= dailyLimit) {
            throw new QuotaExhaustedException("Daily Gemini quota reached");
        }

        if (usage.getRequestCount() >= dailyLimit * 0.8) {
            log.warn("Gemini usage at 80% of daily limit: {}/{}", usage.getRequestCount(), dailyLimit);
        }

        String response = callGeminiApi(request);
        incrementUsage(usage);
        return response;
    }

    private String callGeminiApi(GeminiRequest request) {
        // WebClient call to Gemini REST API
        // Throws RateLimitException on 429 (triggers @Retryable)
    }
}
```

### 5. GitHub Webhook Controller

```java
// backend/src/main/java/com/agentic/controller/WebhookController.java
@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private final WorkflowEngine engine;
    private final GitHubService githubService;
    private final WorkflowLoader workflowLoader;

    @Value("${github.webhook-secret}")
    private String webhookSecret;

    @PostMapping("/github")
    public ResponseEntity<?> handleGithubWebhook(
            @RequestHeader("X-Hub-Signature-256") String signature,
            @RequestHeader("X-GitHub-Event") String event,
            @RequestHeader("X-GitHub-Delivery") String deliveryId,
            @RequestBody String body) {

        if (!verifySignature(body, signature)) {
            return ResponseEntity.status(401).body("Invalid signature");
        }

        JsonNode payload = objectMapper.readTree(body);

        if ("pull_request".equals(event) && isOpenedOrSync(payload)) {
            String diff = githubService.getPRDiff(
                payload.at("/repository/full_name").asText(),
                payload.at("/number").asInt());
            WorkflowDefinition def = workflowLoader.load("pr-review");
            engine.executeWorkflow(def, Map.of("diff", diff, "pr", payload));
        }

        if ("workflow_run".equals(event) && isFailure(payload)) {
            String logs = githubService.getWorkflowLogs(
                payload.at("/repository/full_name").asText(),
                payload.at("/workflow_run/id").asLong());
            WorkflowDefinition def = workflowLoader.load("failure-analysis");
            engine.executeWorkflow(def, Map.of("logs", logs, "run", payload));
        }

        return ResponseEntity.ok(Map.of("received", true));
    }
}
```

### 6. Dashboard Real-time Updates

The Dashboard uses Supabase real-time subscriptions to get live updates:

```typescript
// dashboard/src/lib/supabase.ts
import { createClient } from '@supabase/supabase-js';

const supabase = createClient(
  process.env.NEXT_PUBLIC_SUPABASE_URL!,
  process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY!
);

// Subscribe to workflow status changes
supabase
  .channel('workflow-updates')
  .on('postgres_changes', { event: '*', schema: 'public', table: 'workflow_runs' }, (payload) => {
    // Update UI state
  })
  .on('postgres_changes', { event: 'INSERT', schema: 'public', table: 'review_items' }, (payload) => {
    // Show new review notification
  })
  .subscribe();
```

## API Endpoints (Backend on :8080)

| Method | Path | Description |
|--------|------|-------------|
| POST | /webhook/github | Receives GitHub webhook events |
| GET | /api/workflows | List all workflow runs |
| GET | /api/workflows/{id} | Get workflow run details with steps |
| POST | /api/reviews/{id}/approve | Approve a review item |
| POST | /api/reviews/{id}/reject | Reject a review item (body: { reason }) |
| GET | /api/reviews/pending | List pending review items |
| GET | /api/usage | Get current Gemini usage stats |

## Docker Compose Setup

```yaml
# docker-compose.yml
version: '3.8'

services:
  backend:
    build: ./backend
    ports:
      - "8080:8080"
    env_file: .env
    volumes:
      - ./backend/src/main/resources/workflows:/app/workflows

  dashboard:
    build: ./dashboard
    ports:
      - "3000:3000"
    env_file: .env
    depends_on:
      - backend
```

No database container needed — Supabase is hosted. Backend connects via JDBC URL.

## Environment Variables

```bash
# .env.example
GITHUB_TOKEN=ghp_xxxxxxxxxxxx
GITHUB_WEBHOOK_SECRET=your-webhook-secret
GEMINI_API_KEY=AIza_xxxxxxxxxxxx
GEMINI_DAILY_LIMIT=1500

# Supabase (backend connects via JDBC)
SPRING_DATASOURCE_URL=jdbc:postgresql://db.your-project.supabase.co:5432/postgres
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=your-db-password

# Supabase (dashboard uses JS client for realtime)
NEXT_PUBLIC_SUPABASE_URL=https://your-project.supabase.co
NEXT_PUBLIC_SUPABASE_ANON_KEY=eyJhxxxxxxxxxx
```

## Data Flow: PR Review Workflow

```
1. Developer opens PR on GitHub
2. GitHub sends webhook to POST /webhook/github (port 8080)
3. Spring Boot verifies HMAC-SHA256 signature
4. GitHubService fetches PR diff via GitHub REST API
5. WorkflowEngine starts "pr-review" workflow (async)
6. Step 1: CodeReviewAgent sends diff to Gemini → gets findings JSON
7. Step 2: ChangeSuggestionAgent sends findings to Gemini → gets patch
8. Workflow pauses → creates ReviewItem in Supabase (status: pending)
9. Dashboard receives insert event via Supabase realtime
10. Human clicks "Approve" on Dashboard
11. Dashboard calls POST /api/reviews/{id}/approve (port 8080)
12. WorkflowEngine resumes → GitHubService pushes patch via GitHub API
13. Workflow completes → updates WorkflowRun status to "completed"
```

## Error Handling

| Scenario | Handling |
|----------|----------|
| Gemini rate limit (429) | @Retryable: 3 attempts with exponential backoff (1s, 2s, 4s), then fail step |
| Gemini quota exhausted (100%) | Throw QuotaExhaustedException, preserve workflow state (paused), resume next day |
| GitHub API failure | Fail the step, log error, apply onError action from workflow YAML |
| Invalid webhook signature | Return HTTP 401, do not process |
| Agent timeout (>5 min) | @Async with timeout — mark step as timed_out, apply onError action |
| Supabase/DB unreachable | Spring Retry on repository calls, then log locally |
| Malformed workflow YAML | Reject at load time with descriptive error (app startup validation) |
| Push conflict on approve | Return error to Dashboard, retain patch in review_items for retry |

## Correctness Properties

### Property 1: Workflow Atomicity
Each step either completes fully (output stored in Supabase via @Transactional) or fails entirely (transaction rolled back). There is no scenario where a step's output is partially written.

**Validates: Requirements 5.3, 5.4**

### Property 2: Review State Integrity
A review item can only transition from `pending` → `approved` or `pending` → `rejected`, never backwards. Enforced by a database CHECK constraint and optimistic locking (@Version) on ReviewItem entity.

**Validates: Requirements 4.3, 4.4, 7.3**

### Property 3: Usage Tracking Accuracy
Gemini token count is incremented BEFORE returning success to the agent (within the same @Transactional boundary), ensuring over-counting (safe) rather than under-counting (dangerous).

**Validates: Requirements 8.1, 8.3**

### Property 4: Idempotent Webhooks
Duplicate GitHub events (same X-GitHub-Delivery header) are detected via a unique constraint on delivery_id in workflow_runs. Processing the same event twice returns 200 OK but creates no duplicate run.

**Validates: Requirements 6.1, 10.1**

### Property 5: Ordered Execution
Steps within a workflow always execute in definition order. The WorkflowEngine loop is sequential — no step N+1 starts before step N completes or fails. The workflow_steps table enforces ordering via step_index.

**Validates: Requirements 5.1, 5.2, 5.3**

## Testing Strategy

| Layer | Approach |
|-------|----------|
| Agents | JUnit + Mockito — mock GeminiService, verify input parsing, output structure, error handling |
| Workflow Engine | Spring Boot Test with H2 in-memory DB — verify step sequencing, error paths, pause/resume |
| Webhook Controller | MockMvc tests with sample GitHub payloads — verify signature validation, event routing |
| Gemini Service | WireMock for HTTP mocking — verify retry logic, rate limiting, quota tracking |
| Repositories | @DataJpaTest with H2 — verify queries and state transitions |
| Dashboard | Jest + React Testing Library — verify render states, approve/reject flows |
| End-to-end | Docker Compose + Testcontainers with real Postgres + WireMock for GitHub/Gemini |

## Security Considerations

- Webhook signature verification (HMAC-SHA256) on all incoming GitHub events
- Supabase DB password used server-side only; dashboard uses anon key (read-only via RLS)
- All secrets in `.env` / environment variables, never in application.yml or committed code
- Dashboard on localhost only (not exposed to public internet)
- CORS configured to allow only localhost:3000 → localhost:8080
- No user auth for MVP (single developer, localhost use case)
