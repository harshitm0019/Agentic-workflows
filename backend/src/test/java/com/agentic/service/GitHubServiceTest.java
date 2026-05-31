package com.agentic.service;

import com.agentic.exception.GitHubAuthenticationException;
import com.agentic.exception.GitHubForbiddenException;
import com.agentic.exception.GitHubNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubServiceTest {

    private MockWebServer mockWebServer;
    private GitHubService gitHubService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        String baseUrl = mockWebServer.url("/").toString();
        WebClient.Builder webClientBuilder = WebClient.builder();

        gitHubService = new GitHubService(webClientBuilder, objectMapper, "test-token", baseUrl);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // ===== getPRDiff tests =====

    @Test
    void getPRDiff_success_returnsDiffContent() throws InterruptedException {
        String diffContent = """
                diff --git a/src/Main.java b/src/Main.java
                index abc1234..def5678 100644
                --- a/src/Main.java
                +++ b/src/Main.java
                @@ -1,3 +1,4 @@
                +import java.util.List;
                 public class Main {
                """;

        mockWebServer.enqueue(new MockResponse()
                .setBody(diffContent)
                .addHeader("Content-Type", "text/plain"));

        String result = gitHubService.getPRDiff("owner/repo", 42);

        assertThat(result).isEqualTo(diffContent);

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getPath()).isEqualTo("/repos/owner/repo/pulls/42");
        assertThat(request.getHeader("Accept")).isEqualTo("application/vnd.github.v3.diff");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-token");
    }

    @Test
    void getPRDiff_401_throwsGitHubAuthenticationException() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("{\"message\": \"Bad credentials\"}"));

        assertThatThrownBy(() -> gitHubService.getPRDiff("owner/repo", 1))
                .isInstanceOf(GitHubAuthenticationException.class)
                .hasMessageContaining("authentication failed")
                .hasMessageContaining("owner/repo");
    }

    @Test
    void getPRDiff_403_throwsGitHubForbiddenException() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(403)
                .setBody("{\"message\": \"Forbidden\"}"));

        assertThatThrownBy(() -> gitHubService.getPRDiff("owner/repo", 1))
                .isInstanceOf(GitHubForbiddenException.class)
                .hasMessageContaining("forbidden")
                .hasMessageContaining("owner/repo");
    }

    @Test
    void getPRDiff_404_throwsGitHubNotFoundException() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .setBody("{\"message\": \"Not Found\"}"));

        assertThatThrownBy(() -> gitHubService.getPRDiff("owner/repo", 999))
                .isInstanceOf(GitHubNotFoundException.class)
                .hasMessageContaining("not found")
                .hasMessageContaining("owner/repo")
                .hasMessageContaining("999");
    }

    // ===== getWorkflowLogs tests =====

    @Test
    void getWorkflowLogs_success_extractsLogsFromZip() throws IOException {
        byte[] zipBytes = createZipWithEntries(
                new ZipEntryData("job1/step1.txt", "Step 1 output log line 1\nStep 1 output log line 2"),
                new ZipEntryData("job1/step2.txt", "Step 2 output")
        );

        mockWebServer.enqueue(new MockResponse()
                .setBody(new okio.Buffer().write(zipBytes))
                .addHeader("Content-Type", "application/zip"));

        String result = gitHubService.getWorkflowLogs("owner/repo", 12345L);

        assertThat(result).contains("=== job1/step1.txt ===");
        assertThat(result).contains("Step 1 output log line 1");
        assertThat(result).contains("Step 1 output log line 2");
        assertThat(result).contains("=== job1/step2.txt ===");
        assertThat(result).contains("Step 2 output");
    }

    @Test
    void getWorkflowLogs_truncatesTo50KChars() throws IOException {
        // Create a log entry that exceeds 50K characters
        String longContent = "x".repeat(60_000);
        byte[] zipBytes = createZipWithEntries(
                new ZipEntryData("job/long_log.txt", longContent)
        );

        mockWebServer.enqueue(new MockResponse()
                .setBody(new okio.Buffer().write(zipBytes))
                .addHeader("Content-Type", "application/zip"));

        String result = gitHubService.getWorkflowLogs("owner/repo", 12345L);

        assertThat(result.length()).isLessThanOrEqualTo(50_000);
    }

    @Test
    void getWorkflowLogs_401_throwsGitHubAuthenticationException() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("{\"message\": \"Bad credentials\"}"));

        assertThatThrownBy(() -> gitHubService.getWorkflowLogs("owner/repo", 123L))
                .isInstanceOf(GitHubAuthenticationException.class)
                .hasMessageContaining("authentication failed");
    }

    @Test
    void getWorkflowLogs_403_throwsGitHubForbiddenException() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(403)
                .setBody("{\"message\": \"Forbidden\"}"));

        assertThatThrownBy(() -> gitHubService.getWorkflowLogs("owner/repo", 123L))
                .isInstanceOf(GitHubForbiddenException.class)
                .hasMessageContaining("forbidden");
    }

    @Test
    void getWorkflowLogs_404_throwsGitHubNotFoundException() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .setBody("{\"message\": \"Not Found\"}"));

        assertThatThrownBy(() -> gitHubService.getWorkflowLogs("owner/repo", 999L))
                .isInstanceOf(GitHubNotFoundException.class)
                .hasMessageContaining("not found")
                .hasMessageContaining("999");
    }

    @Test
    void getWorkflowLogs_emptyZip_returnsEmptyString() throws IOException {
        byte[] emptyZip = createZipWithEntries(); // empty zip

        mockWebServer.enqueue(new MockResponse()
                .setBody(new okio.Buffer().write(emptyZip))
                .addHeader("Content-Type", "application/zip"));

        String result = gitHubService.getWorkflowLogs("owner/repo", 123L);

        assertThat(result).isEmpty();
    }

    // ===== pushCommit tests =====

    @Test
    void pushCommit_createNewFile_sendsCorrectRequest() throws InterruptedException {
        // First request: GET file SHA returns 404 (file doesn't exist)
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .setBody("{\"message\": \"Not Found\"}"));

        // Second request: PUT to create file
        String pushResponse = """
                {
                    "content": {
                        "name": "hello.txt",
                        "path": "src/hello.txt",
                        "sha": "abc123"
                    },
                    "commit": {
                        "sha": "commit-sha-12345",
                        "message": "Add hello.txt"
                    }
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(pushResponse)
                .addHeader("Content-Type", "application/json"));

        String result = gitHubService.pushCommit("owner/repo", "main", "src/hello.txt", "Hello World", "Add hello.txt");

        assertThat(result).isEqualTo("commit-sha-12345");

        // Verify the GET request for file SHA
        RecordedRequest getRequest = mockWebServer.takeRequest();
        assertThat(getRequest.getMethod()).isEqualTo("GET");
        assertThat(getRequest.getPath()).contains("/repos/owner/repo/contents/src/hello.txt");

        // Verify the PUT request
        RecordedRequest putRequest = mockWebServer.takeRequest();
        assertThat(putRequest.getMethod()).isEqualTo("PUT");
        assertThat(putRequest.getPath()).isEqualTo("/repos/owner/repo/contents/src/hello.txt");

        String body = putRequest.getBody().readUtf8();
        assertThat(body).contains("\"message\":\"Add hello.txt\"");
        assertThat(body).contains("\"branch\":\"main\"");
        String expectedBase64 = Base64.getEncoder().encodeToString("Hello World".getBytes(StandardCharsets.UTF_8));
        assertThat(body).contains(expectedBase64);
        // Should not contain sha since it's a new file
        assertThat(body).doesNotContain("\"sha\"");
    }

    @Test
    void pushCommit_updateExistingFile_includesSha() throws InterruptedException {
        // First request: GET file SHA returns existing file
        String fileResponse = """
                {
                    "name": "hello.txt",
                    "path": "src/hello.txt",
                    "sha": "existing-file-sha-789"
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(fileResponse)
                .addHeader("Content-Type", "application/json"));

        // Second request: PUT to update file
        String pushResponse = """
                {
                    "content": {
                        "name": "hello.txt",
                        "path": "src/hello.txt",
                        "sha": "new-file-sha"
                    },
                    "commit": {
                        "sha": "update-commit-sha-456",
                        "message": "Update hello.txt"
                    }
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(pushResponse)
                .addHeader("Content-Type", "application/json"));

        String result = gitHubService.pushCommit("owner/repo", "feature-branch", "src/hello.txt", "Updated content", "Update hello.txt");

        assertThat(result).isEqualTo("update-commit-sha-456");

        // Skip GET request
        mockWebServer.takeRequest();

        // Verify PUT includes SHA
        RecordedRequest putRequest = mockWebServer.takeRequest();
        String body = putRequest.getBody().readUtf8();
        assertThat(body).contains("existing-file-sha-789");
        assertThat(body).contains("\"branch\":\"feature-branch\"");
    }

    @Test
    void pushCommit_401_throwsGitHubAuthenticationException() {
        // GET for SHA returns 404 (new file)
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .setBody("{\"message\": \"Not Found\"}"));

        // PUT returns 401
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("{\"message\": \"Bad credentials\"}"));

        assertThatThrownBy(() -> gitHubService.pushCommit("owner/repo", "main", "file.txt", "content", "msg"))
                .isInstanceOf(GitHubAuthenticationException.class)
                .hasMessageContaining("authentication failed");
    }

    @Test
    void pushCommit_403_throwsGitHubForbiddenException() {
        // GET for SHA returns 404 (new file)
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .setBody("{\"message\": \"Not Found\"}"));

        // PUT returns 403
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(403)
                .setBody("{\"message\": \"Forbidden\"}"));

        assertThatThrownBy(() -> gitHubService.pushCommit("owner/repo", "main", "file.txt", "content", "msg"))
                .isInstanceOf(GitHubForbiddenException.class)
                .hasMessageContaining("forbidden");
    }

    @Test
    void pushCommit_404_throwsGitHubNotFoundException() {
        // GET for SHA returns 404 (new file)
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .setBody("{\"message\": \"Not Found\"}"));

        // PUT returns 404 (repo not found)
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .setBody("{\"message\": \"Not Found\"}"));

        assertThatThrownBy(() -> gitHubService.pushCommit("owner/nonexistent", "main", "file.txt", "content", "msg"))
                .isInstanceOf(GitHubNotFoundException.class)
                .hasMessageContaining("not found");
    }

    // ===== extractLogsFromZip tests =====

    @Test
    void extractLogsFromZip_nullInput_returnsEmpty() {
        String result = gitHubService.extractLogsFromZip(null);
        assertThat(result).isEmpty();
    }

    @Test
    void extractLogsFromZip_emptyInput_returnsEmpty() {
        String result = gitHubService.extractLogsFromZip(new byte[0]);
        assertThat(result).isEmpty();
    }

    // ===== Helper methods =====

    private byte[] createZipWithEntries(ZipEntryData... entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (ZipEntryData entry : entries) {
                zos.putNextEntry(new ZipEntry(entry.name()));
                zos.write(entry.content().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private record ZipEntryData(String name, String content) {}
}
