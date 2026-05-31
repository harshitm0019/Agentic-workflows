package com.agentic.controller;

import com.agentic.engine.WorkflowDefinition;
import com.agentic.engine.WorkflowEngine;
import com.agentic.engine.WorkflowLoader;
import com.agentic.engine.WorkflowStep;
import com.agentic.repository.WorkflowRunRepository;
import com.agentic.service.GitHubService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WebhookController.class)
@ActiveProfiles("test")
class WebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkflowEngine workflowEngine;

    @MockBean
    private GitHubService gitHubService;

    @MockBean
    private WorkflowLoader workflowLoader;

    @MockBean
    private WorkflowRunRepository workflowRunRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String WEBHOOK_SECRET = "test-secret";
    private static final String DELIVERY_ID = "test-delivery-123";

    private WorkflowDefinition prReviewWorkflow;
    private WorkflowDefinition failureAnalysisWorkflow;

    @BeforeEach
    void setUp() {
        prReviewWorkflow = new WorkflowDefinition(
                "pr-review", "pull_request", "Reviews PR code",
                List.of(new WorkflowStep("review-code", "code-review",
                        Map.of("diff", "{{trigger.diff}}"), "halt", false))
        );

        failureAnalysisWorkflow = new WorkflowDefinition(
                "failure-analysis", "workflow_run_failure", "Analyzes failures",
                List.of(new WorkflowStep("analyze-logs", "log-analysis",
                        Map.of("logs", "{{trigger.logs}}"), "halt", false))
        );
    }

    @Test
    void handleGithubWebhook_validSignature_pullRequestOpened_returns200() throws Exception {
        String body = buildPullRequestPayload("opened");
        String signature = computeSignature(body);

        when(workflowRunRepository.existsByDeliveryId(DELIVERY_ID)).thenReturn(false);
        when(gitHubService.getPRDiff("owner/repo", 42)).thenReturn("diff content");
        when(workflowLoader.load("pr-review")).thenReturn(prReviewWorkflow);

        mockMvc.perform(post("/webhook/github")
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", DELIVERY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

        verify(gitHubService).getPRDiff("owner/repo", 42);
        verify(workflowEngine).executeWorkflow(eq(prReviewWorkflow), anyMap());
    }

    @Test
    void handleGithubWebhook_validSignature_pullRequestSynchronize_returns200() throws Exception {
        String body = buildPullRequestPayload("synchronize");
        String signature = computeSignature(body);

        when(workflowRunRepository.existsByDeliveryId(DELIVERY_ID)).thenReturn(false);
        when(gitHubService.getPRDiff("owner/repo", 42)).thenReturn("diff content");
        when(workflowLoader.load("pr-review")).thenReturn(prReviewWorkflow);

        mockMvc.perform(post("/webhook/github")
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", DELIVERY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

        verify(gitHubService).getPRDiff("owner/repo", 42);
        verify(workflowEngine).executeWorkflow(eq(prReviewWorkflow), anyMap());
    }

    @Test
    void handleGithubWebhook_validSignature_workflowRunFailure_returns200() throws Exception {
        String body = buildWorkflowRunPayload("failure");
        String signature = computeSignature(body);

        when(workflowRunRepository.existsByDeliveryId(DELIVERY_ID)).thenReturn(false);
        when(gitHubService.getWorkflowLogs("owner/repo", 12345L)).thenReturn("log content");
        when(workflowLoader.load("failure-analysis")).thenReturn(failureAnalysisWorkflow);

        mockMvc.perform(post("/webhook/github")
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Event", "workflow_run")
                        .header("X-GitHub-Delivery", DELIVERY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

        verify(gitHubService).getWorkflowLogs("owner/repo", 12345L);
        verify(workflowEngine).executeWorkflow(eq(failureAnalysisWorkflow), anyMap());
    }

    @Test
    void handleGithubWebhook_invalidSignature_returns401() throws Exception {
        String body = buildPullRequestPayload("opened");

        mockMvc.perform(post("/webhook/github")
                        .header("X-Hub-Signature-256", "sha256=invalid")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", DELIVERY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid signature"));

        verifyNoInteractions(workflowEngine);
        verifyNoInteractions(gitHubService);
    }

    @Test
    void handleGithubWebhook_missingSignaturePrefix_returns401() throws Exception {
        String body = buildPullRequestPayload("opened");

        mockMvc.perform(post("/webhook/github")
                        .header("X-Hub-Signature-256", "not-sha256-prefix")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", DELIVERY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid signature"));

        verifyNoInteractions(workflowEngine);
    }

    @Test
    void handleGithubWebhook_duplicateDeliveryId_returns200WithoutProcessing() throws Exception {
        String body = buildPullRequestPayload("opened");
        String signature = computeSignature(body);

        when(workflowRunRepository.existsByDeliveryId(DELIVERY_ID)).thenReturn(true);

        mockMvc.perform(post("/webhook/github")
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", DELIVERY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true))
                .andExpect(jsonPath("$.duplicate").value(true));

        verifyNoInteractions(workflowEngine);
        verifyNoInteractions(gitHubService);
    }

    @Test
    void handleGithubWebhook_pullRequestClosed_returns200WithoutTriggeringWorkflow() throws Exception {
        String body = buildPullRequestPayload("closed");
        String signature = computeSignature(body);

        when(workflowRunRepository.existsByDeliveryId(DELIVERY_ID)).thenReturn(false);

        mockMvc.perform(post("/webhook/github")
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", DELIVERY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

        verifyNoInteractions(workflowEngine);
        verifyNoInteractions(gitHubService);
    }

    @Test
    void handleGithubWebhook_workflowRunSuccess_returns200WithoutTriggeringWorkflow() throws Exception {
        String body = buildWorkflowRunPayload("success");
        String signature = computeSignature(body);

        when(workflowRunRepository.existsByDeliveryId(DELIVERY_ID)).thenReturn(false);

        mockMvc.perform(post("/webhook/github")
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Event", "workflow_run")
                        .header("X-GitHub-Delivery", DELIVERY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

        verifyNoInteractions(workflowEngine);
        verifyNoInteractions(gitHubService);
    }

    @Test
    void handleGithubWebhook_missingHeaders_returns400() throws Exception {
        String body = buildPullRequestPayload("opened");

        // Missing X-Hub-Signature-256 header
        mockMvc.perform(post("/webhook/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", DELIVERY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void handleGithubWebhook_unknownEvent_returns200() throws Exception {
        String body = "{\"action\":\"completed\"}";
        String signature = computeSignature(body);

        when(workflowRunRepository.existsByDeliveryId(DELIVERY_ID)).thenReturn(false);

        mockMvc.perform(post("/webhook/github")
                        .header("X-Hub-Signature-256", signature)
                        .header("X-GitHub-Event", "push")
                        .header("X-GitHub-Delivery", DELIVERY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

        verifyNoInteractions(workflowEngine);
        verifyNoInteractions(gitHubService);
    }

    // --- Helper methods ---

    private String computeSignature(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(
                WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return "sha256=" + HexFormat.of().formatHex(hmacBytes);
    }

    private String buildPullRequestPayload(String action) throws Exception {
        Map<String, Object> payload = Map.of(
                "action", action,
                "pull_request", Map.of("number", 42),
                "repository", Map.of("full_name", "owner/repo")
        );
        return objectMapper.writeValueAsString(payload);
    }

    private String buildWorkflowRunPayload(String conclusion) throws Exception {
        Map<String, Object> payload = Map.of(
                "action", "completed",
                "workflow_run", Map.of("id", 12345, "conclusion", conclusion),
                "repository", Map.of("full_name", "owner/repo")
        );
        return objectMapper.writeValueAsString(payload);
    }
}
