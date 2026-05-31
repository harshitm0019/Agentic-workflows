package com.agentic.controller;

import com.agentic.engine.WorkflowDefinition;
import com.agentic.engine.WorkflowEngine;
import com.agentic.engine.WorkflowLoader;
import com.agentic.repository.WorkflowRunRepository;
import com.agentic.service.GitHubService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Receives GitHub webhook events, verifies HMAC-SHA256 signatures,
 * and dispatches to the appropriate workflow.
 */
@RestController
@RequestMapping("/webhook")
@Slf4j
public class WebhookController {

    private final WorkflowEngine engine;
    private final GitHubService githubService;
    private final WorkflowLoader workflowLoader;
    private final WorkflowRunRepository workflowRunRepository;
    private final ObjectMapper objectMapper;

    @Value("${github.webhook-secret}")
    private String webhookSecret;

    public WebhookController(WorkflowEngine engine,
                             GitHubService githubService,
                             WorkflowLoader workflowLoader,
                             WorkflowRunRepository workflowRunRepository,
                             ObjectMapper objectMapper) {
        this.engine = engine;
        this.githubService = githubService;
        this.workflowLoader = workflowLoader;
        this.workflowRunRepository = workflowRunRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/github")
    public ResponseEntity<?> handleGithubWebhook(
            @RequestHeader("X-Hub-Signature-256") String signature,
            @RequestHeader("X-GitHub-Event") String event,
            @RequestHeader("X-GitHub-Delivery") String deliveryId,
            @RequestBody String body) {

        // Verify HMAC-SHA256 signature
        if (!verifySignature(body, signature)) {
            log.warn("Invalid webhook signature for delivery {}", deliveryId);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid signature"));
        }

        // Deduplicate via delivery ID
        if (workflowRunRepository.existsByDeliveryId(deliveryId)) {
            log.info("Duplicate delivery ID: {}, skipping", deliveryId);
            return ResponseEntity.ok(Map.of("received", true, "duplicate", true));
        }

        try {
            JsonNode payload = objectMapper.readTree(body);

            if ("pull_request".equals(event) && isOpenedOrSynchronize(payload)) {
                handlePullRequest(payload, deliveryId);
            } else if ("workflow_run".equals(event) && isFailure(payload)) {
                handleWorkflowRunFailure(payload, deliveryId);
            } else {
                log.debug("Ignoring event: {} (action: {})", event,
                        payload.has("action") ? payload.get("action").asText() : "none");
            }

            return ResponseEntity.ok(Map.of("received", true));
        } catch (IllegalArgumentException e) {
            log.error("Workflow not found: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("received", true, "warning", e.getMessage()));
        } catch (Exception e) {
            log.error("Error processing webhook: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Internal processing error"));
        }
    }

    private void handlePullRequest(JsonNode payload, String deliveryId) {
        String repoFullName = payload.at("/repository/full_name").asText();
        int prNumber = payload.at("/pull_request/number").asInt();

        // Ignore commits made by the platform itself to prevent infinite loops
        String headSha = payload.at("/pull_request/head/sha").asText("");
        String senderLogin = payload.at("/sender/login").asText("");
        String commitMessage = payload.at("/pull_request/title").asText("");

        // Check if the latest commit was made by our platform
        // We identify our own commits by the commit message prefix
        if (isOwnCommit(repoFullName, headSha)) {
            log.info("Ignoring PR event triggered by our own commit on {}/pull/{}", repoFullName, prNumber);
            return;
        }

        log.info("Processing pull_request event for {}/pull/{}", repoFullName, prNumber);

        String diff = githubService.getPRDiff(repoFullName, prNumber);
        WorkflowDefinition def = workflowLoader.load("pr-review");

        Map<String, Object> triggerPayload = Map.of(
                "diff", diff,
                "repo", repoFullName,
                "pr_number", prNumber,
                "delivery_id", deliveryId
        );

        engine.executeWorkflow(def, triggerPayload);
    }

    private void handleWorkflowRunFailure(JsonNode payload, String deliveryId) {
        String repoFullName = payload.at("/repository/full_name").asText();
        long runId = payload.at("/workflow_run/id").asLong();

        log.info("Processing workflow_run failure for {}/actions/runs/{}", repoFullName, runId);

        String logs = githubService.getWorkflowLogs(repoFullName, runId);
        WorkflowDefinition def = workflowLoader.load("failure-analysis");

        Map<String, Object> triggerPayload = Map.of(
                "logs", logs,
                "repo", repoFullName,
                "run_id", runId,
                "delivery_id", deliveryId
        );

        engine.executeWorkflow(def, triggerPayload);
    }

    private boolean isOpenedOrSynchronize(JsonNode payload) {
        String action = payload.path("action").asText("");
        return "opened".equals(action) || "synchronize".equals(action);
    }

    private boolean isFailure(JsonNode payload) {
        String conclusion = payload.at("/workflow_run/conclusion").asText("");
        return "failure".equals(conclusion);
    }

    /**
     * Verifies the HMAC-SHA256 signature of the webhook payload.
     * The signature header format is "sha256=<hex-digest>".
     */
    boolean verifySignature(String payload, String signatureHeader) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.warn("Webhook secret is not configured");
            return false;
        }

        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);

            byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expectedSignature = "sha256=" + HexFormat.of().formatHex(hmacBytes);

            return constantTimeEquals(expectedSignature, signatureHeader);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Error computing HMAC signature", e);
            return false;
        }
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    /**
     * Checks if the given commit SHA was made by our platform (to prevent infinite loops).
     * We identify our commits by the commit message starting with "fix: apply suggested changes from Agentic Workflows".
     */
    private boolean isOwnCommit(String repoFullName, String sha) {
        if (sha == null || sha.isBlank()) return false;
        try {
            String commitMessage = githubService.getCommitMessage(repoFullName, sha);
            return commitMessage != null && commitMessage.startsWith("fix: apply suggested changes from Agentic Workflows");
        } catch (Exception e) {
            log.debug("Could not check commit {}: {}", sha, e.getMessage());
            return false;
        }
    }
}
