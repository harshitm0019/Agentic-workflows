# Requirements Document

## Introduction

The Agentic Workflows Platform (MVP) is a lightweight system that orchestrates specialized AI agents to automate software development tasks. The MVP focuses on three core agents (code review, log analysis, change suggestion) connected through simple sequential workflows, triggered by GitHub Actions events.

The MVP prioritizes getting a working end-to-end flow: GitHub event → agent analysis → human review → action. Advanced features like parallel execution, plugin architecture, and complex A2A protocols are deferred to future phases.

**Tech Stack:** TypeScript/Node.js for backend (simpler, single-language stack for MVP), React/Next.js for dashboard, Docker for local deployment, Gemini free-tier for LLM.

## Glossary

- **Platform**: The Agentic Workflows system as a whole
- **Agent**: A TypeScript module that performs a specific task using Gemini API and optional tool calls
- **Workflow_Engine**: A simple sequential step executor that runs agents in order and passes data between them
- **Agent_Registry**: A configuration file (JSON/YAML) listing available agents and their capabilities
- **MCP_Server**: A Model Context Protocol server exposing tools to agents
- **Dashboard**: A Next.js web app for monitoring workflows and approving/rejecting agent suggestions
- **Workflow_Definition**: A YAML file defining a sequence of agent steps with simple if/else branching

## MVP Scope

**In Scope:**
- 3 agents: Code Review, Log Analysis, Change Suggestion
- Sequential workflow execution (steps run one after another)
- Simple branching (if step fails → error path, if succeeds → next step)
- GitHub Actions webhook trigger (PR created, pipeline failed)
- Web dashboard for human review/approval
- Gemini free-tier rate limiting
- Docker Compose for local deployment

**Out of Scope (Future Phases):**
- Parallel workflow execution
- Plugin architecture for external repos
- Custom MCP server creation
- Agent-to-Agent direct messaging (agents communicate through workflow data passing only)
- Multi-user authentication (single-user for MVP)
- Agent hot-reload or dynamic registration

## Requirements

### Requirement 1: Agent Configuration

**User Story:** As a developer, I want to define agents in a configuration file, so that the platform knows which agents are available and how to invoke them.

#### Acceptance Criteria

1. THE Platform SHALL load agent definitions from a `agents.yaml` file containing name, description, capabilities list, Gemini model config (model name, temperature, max tokens, system prompt), and required tools
2. WHEN the Platform starts, THE Platform SHALL validate all agent definitions and log errors for any agent with missing required fields (name, capabilities, system prompt)
3. IF an agent definition is invalid, THEN THE Platform SHALL skip that agent and continue loading the remaining valid agents
4. THE Platform SHALL provide agent definitions for: code-review, log-analysis, and change-suggestion as defaults

### Requirement 2: Code Review Agent

**User Story:** As a developer, I want an automated code review agent, so that pull requests receive immediate feedback.

#### Acceptance Criteria

1. WHEN a code diff is provided, THE Code_Review_Agent SHALL send the diff to Gemini API with a code-review system prompt and return findings as a JSON array with fields: filePath, lineNumber, severity (error|warning|suggestion), and message
2. THE Code_Review_Agent SHALL complete analysis within 60 seconds for diffs up to 500 lines
3. IF the diff exceeds 2000 lines, THEN THE Code_Review_Agent SHALL reject the input with a size-limit error
4. IF Gemini API returns a rate-limit error, THEN THE Code_Review_Agent SHALL retry up to 3 times with exponential backoff (1s, 2s, 4s) before returning a failure response
5. IF the diff has no issues, THEN THE Code_Review_Agent SHALL return an empty findings array with a summary message

### Requirement 3: Log Analysis Agent

**User Story:** As a developer, I want an agent that analyzes failure logs, so that I can quickly understand what went wrong.

#### Acceptance Criteria

1. WHEN logs are provided, THE Log_Analysis_Agent SHALL send them to Gemini API and return a JSON report containing: errorType, rootCause, affectedComponents (array), suggestedFix, and confidenceScore (0.0-1.0)
2. THE Log_Analysis_Agent SHALL accept Docker container logs and GitHub Actions logs
3. WHEN log input exceeds 50,000 characters, THE Log_Analysis_Agent SHALL truncate to the last 20,000 characters (keeping the most recent errors) before sending to Gemini
4. IF no failure is detected, THEN THE Log_Analysis_Agent SHALL return a report with errorType "none" and confidenceScore indicating certainty
5. IF Gemini API is unavailable, THEN THE Log_Analysis_Agent SHALL retry up to 3 times before returning a failure response

### Requirement 4: Change Suggestion Agent

**User Story:** As a developer, I want an agent that suggests code fixes that I can approve before they're applied.

#### Acceptance Criteria

1. WHEN a failure report or review finding is provided, THE Change_Suggestion_Agent SHALL generate a code patch in unified diff format and return it as a string
2. THE Platform SHALL present the patch on the Dashboard with: affected files, diff content, and the originating issue
3. WHEN a human clicks "Approve", THE Platform SHALL apply the patch to the target branch via GitHub API and display the resulting commit SHA
4. WHEN a human clicks "Reject", THE Platform SHALL discard the patch and log the rejection
5. IF the Change_Suggestion_Agent cannot generate a fix, THEN it SHALL return a message explaining why and suggesting manual intervention
6. IF the push fails (merge conflict, permission error), THEN THE Platform SHALL display the error on the Dashboard

### Requirement 5: Workflow Execution

**User Story:** As a developer, I want to define sequential workflows that run agents in order, so that I can automate multi-step processes.

#### Acceptance Criteria

1. THE Workflow_Engine SHALL execute steps sequentially as defined in a YAML workflow file
2. EACH workflow step SHALL specify: agent name, input mapping (which previous step's output to use), and an optional on-error action (skip, halt, or goto step)
3. WHEN a step completes, THE Workflow_Engine SHALL store its output and pass it as input to the next step
4. IF a step fails and on-error is "halt", THEN THE Workflow_Engine SHALL stop execution and notify via Dashboard
5. IF a step fails and on-error is "skip", THEN THE Workflow_Engine SHALL continue to the next step
6. THE Workflow_Engine SHALL support a "human-review" step type that pauses execution until a human approves or rejects on the Dashboard
7. IF a workflow step exceeds 5 minutes, THEN THE Workflow_Engine SHALL mark it as timed-out and apply the on-error action

### Requirement 6: GitHub Actions Integration

**User Story:** As a developer, I want workflows to trigger automatically from GitHub events, so that the platform reacts to PRs and failures without manual intervention.

#### Acceptance Criteria

1. THE Platform SHALL expose a webhook endpoint that receives GitHub Actions events (pull_request opened/synchronize, workflow_run completed with failure)
2. WHEN a pull_request event is received, THE Platform SHALL extract the diff via GitHub API and trigger the configured PR workflow
3. WHEN a workflow_run failure event is received, THE Platform SHALL retrieve the failure logs via GitHub API and trigger the configured failure workflow
4. THE Platform SHALL authenticate with GitHub using a personal access token stored in environment variables
5. IF the GitHub token is invalid, THEN THE Platform SHALL return a 401 error on the webhook and log the authentication failure
6. THE Platform SHALL include a sample GitHub Actions YAML that sends webhook events to the platform

### Requirement 7: Human Review Dashboard

**User Story:** As a developer, I want a simple web interface to see what agents are doing and approve/reject their suggestions.

#### Acceptance Criteria

1. THE Dashboard SHALL display a list of active and completed workflows with their current status (running, paused-for-review, completed, failed)
2. THE Dashboard SHALL display pending review items showing: agent name, workflow name, what action needs approval, and the agent's output (diff, report, etc.)
3. WHEN a user clicks Approve or Reject on a review item, THE Dashboard SHALL notify the Workflow_Engine and update the UI within 2 seconds
4. THE Dashboard SHALL auto-refresh workflow status every 5 seconds (or use WebSocket for real-time updates)
5. THE Dashboard SHALL be accessible on localhost:3000 when running via Docker Compose

### Requirement 8: Gemini Rate Limit Management

**User Story:** As a developer, I want the platform to stay within Gemini free-tier limits, so that I don't get blocked or charged.

#### Acceptance Criteria

1. THE Platform SHALL track the number of Gemini API requests and total tokens used per day, resetting at midnight UTC
2. WHEN usage reaches 80% of the daily free-tier limit, THE Platform SHALL log a warning on the Dashboard
3. WHEN usage reaches 100%, THE Platform SHALL queue new agent requests and resume them the next day, preserving workflow state
4. THE Platform SHALL display current usage (requests made / limit, tokens used / limit) on the Dashboard

### Requirement 9: Local Deployment

**User Story:** As a developer, I want to run the entire platform locally with a single command, so that setup is fast and free.

#### Acceptance Criteria

1. THE Platform SHALL provide a `docker-compose.yml` that starts all services (backend API, Dashboard, and any MCP servers) with a single `docker-compose up` command
2. THE Platform SHALL use environment variables (via `.env` file) for configuration: GitHub token, Gemini API key, and webhook secret
3. THE Platform SHALL include a README with setup instructions covering: prerequisites, environment setup, first run, and triggering a sample workflow
4. ALL services SHALL be healthy and accepting requests within 60 seconds of `docker-compose up`

### Requirement 10: Security Basics

**User Story:** As a developer, I want basic security so that my tokens are safe and only valid GitHub events trigger workflows.

#### Acceptance Criteria

1. THE Platform SHALL validate incoming webhook requests using a GitHub webhook secret (HMAC-SHA256 signature verification)
2. THE Platform SHALL never log or expose API keys, tokens, or secrets in responses or dashboard UI
3. THE Platform SHALL store secrets only in environment variables, never in code or config files committed to the repository
4. THE Dashboard SHALL be accessible only on localhost (not exposed to public internet) in the default configuration
