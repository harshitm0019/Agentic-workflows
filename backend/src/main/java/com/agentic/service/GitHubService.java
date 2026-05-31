package com.agentic.service;

import com.agentic.exception.GitHubAuthenticationException;
import com.agentic.exception.GitHubForbiddenException;
import com.agentic.exception.GitHubNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@Slf4j
public class GitHubService {

    private static final int MAX_LOG_CHARS = 50_000;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public GitHubService(WebClient.Builder webClientBuilder,
                         ObjectMapper objectMapper,
                         @Value("${github.token}") String token,
                         @Value("${github.base-url:https://api.github.com}") String baseUrl) {
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /**
     * Fetches the diff for a pull request from GitHub API.
     *
     * @param repoFullName the full repository name (e.g., "owner/repo")
     * @param prNumber     the pull request number
     * @return the diff content as a string
     */
    public String getPRDiff(String repoFullName, int prNumber) {
        log.info("Fetching PR diff for {}/pull/{}", repoFullName, prNumber);

        return webClient.get()
                .uri("/repos/" + repoFullName + "/pulls/" + prNumber)
                .header(HttpHeaders.ACCEPT, "application/vnd.github.v3.diff")
                .retrieve()
                .onStatus(status -> status.value() == 401,
                        response -> Mono.error(new GitHubAuthenticationException(
                                "GitHub authentication failed for PR diff: " + repoFullName + "#" + prNumber)))
                .onStatus(status -> status.value() == 403,
                        response -> Mono.error(new GitHubForbiddenException(
                                "Access forbidden to PR diff: " + repoFullName + "#" + prNumber)))
                .onStatus(status -> status.value() == 404,
                        response -> Mono.error(new GitHubNotFoundException(
                                "PR not found: " + repoFullName + "#" + prNumber)))
                .onStatus(HttpStatusCode::isError,
                        response -> response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new RuntimeException(
                                        "GitHub API error fetching PR diff: " + body))))
                .bodyToMono(String.class)
                .block();
    }

    /**
     * Fetches workflow run logs from GitHub API.
     * Logs are returned as a zip file; this method extracts and concatenates text content,
     * truncating to 50,000 characters.
     *
     * @param repoFullName the full repository name (e.g., "owner/repo")
     * @param runId        the workflow run ID
     * @return the log content as a string, truncated to 50K chars
     */
    public String getWorkflowLogs(String repoFullName, long runId) {
        log.info("Fetching workflow logs for {}/actions/runs/{}", repoFullName, runId);

        byte[] zipBytes = webClient.get()
                .uri("/repos/" + repoFullName + "/actions/runs/" + runId + "/logs")
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .retrieve()
                .onStatus(status -> status.value() == 401,
                        response -> Mono.error(new GitHubAuthenticationException(
                                "GitHub authentication failed for workflow logs: " + repoFullName + " run " + runId)))
                .onStatus(status -> status.value() == 403,
                        response -> Mono.error(new GitHubForbiddenException(
                                "Access forbidden to workflow logs: " + repoFullName + " run " + runId)))
                .onStatus(status -> status.value() == 404,
                        response -> Mono.error(new GitHubNotFoundException(
                                "Workflow run not found: " + repoFullName + " run " + runId)))
                .onStatus(HttpStatusCode::isError,
                        response -> response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new RuntimeException(
                                        "GitHub API error fetching workflow logs: " + body))))
                .bodyToMono(byte[].class)
                .block();

        return extractLogsFromZip(zipBytes);
    }

    /**
     * Creates or updates a file in a GitHub repository via the Contents API.
     *
     * @param repoFullName the full repository name (e.g., "owner/repo")
     * @param branch       the target branch
     * @param filePath     the path of the file within the repository
     * @param content      the file content
     * @param message      the commit message
     * @return the commit SHA from the response
     */
    public String pushCommit(String repoFullName, String branch, String filePath, String content, String message) {
        log.info("Pushing commit to {}/{} on branch {}", repoFullName, filePath, branch);

        String encodedContent = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));

        // Try to get the existing file SHA for updates
        String existingSha = getFileSha(repoFullName, filePath, branch);

        Map<String, Object> requestBody;
        if (existingSha != null) {
            requestBody = Map.of(
                    "message", message,
                    "content", encodedContent,
                    "branch", branch,
                    "sha", existingSha
            );
        } else {
            requestBody = Map.of(
                    "message", message,
                    "content", encodedContent,
                    "branch", branch
            );
        }

        String responseBody = webClient.put()
                .uri("/repos/" + repoFullName + "/contents/" + filePath)
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(status -> status.value() == 401,
                        response -> Mono.error(new GitHubAuthenticationException(
                                "GitHub authentication failed for push: " + repoFullName + "/" + filePath)))
                .onStatus(status -> status.value() == 403,
                        response -> Mono.error(new GitHubForbiddenException(
                                "Access forbidden to push: " + repoFullName + "/" + filePath)))
                .onStatus(status -> status.value() == 404,
                        response -> Mono.error(new GitHubNotFoundException(
                                "Repository or path not found: " + repoFullName + "/" + filePath)))
                .onStatus(HttpStatusCode::isError,
                        response -> response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new RuntimeException(
                                        "GitHub API error pushing commit: " + body))))
                .bodyToMono(String.class)
                .block();

        return extractCommitSha(responseBody);
    }

    /**
     * Gets the SHA of an existing file for update operations.
     * Returns null if the file doesn't exist (for create operations).
     */
    String getFileSha(String repoFullName, String filePath, String branch) {
        try {
            String responseBody = webClient.get()
                    .uri("/repos/" + repoFullName + "/contents/" + filePath + "?ref=" + branch)
                    .retrieve()
                    .onStatus(status -> status.value() == 404,
                            response -> Mono.empty())
                    .onStatus(HttpStatusCode::isError,
                            response -> Mono.empty())
                    .bodyToMono(String.class)
                    .block();

            if (responseBody != null) {
                JsonNode node = objectMapper.readTree(responseBody);
                return node.path("sha").asText(null);
            }
        } catch (Exception e) {
            log.debug("File not found at {}/{}, will create new: {}", repoFullName, filePath, e.getMessage());
        }
        return null;
    }

    String extractLogsFromZip(byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0) {
            return "";
        }

        StringBuilder logs = new StringBuilder();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    byte[] entryBytes = zis.readAllBytes();
                    String entryContent = new String(entryBytes, StandardCharsets.UTF_8);
                    logs.append("=== ").append(entry.getName()).append(" ===\n");
                    logs.append(entryContent).append("\n");
                }
                zis.closeEntry();

                if (logs.length() >= MAX_LOG_CHARS) {
                    break;
                }
            }
        } catch (IOException e) {
            log.error("Failed to extract logs from zip", e);
            return logs.toString();
        }

        if (logs.length() > MAX_LOG_CHARS) {
            return logs.substring(0, MAX_LOG_CHARS);
        }
        return logs.toString();
    }

    private String extractCommitSha(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return root.path("commit").path("sha").asText("");
        } catch (Exception e) {
            log.error("Failed to parse push commit response", e);
            return "";
        }
    }

    /**
     * Applies a unified diff patch by pushing the fixed file(s) as a commit to the PR branch.
     * Parses the patch to extract file paths, gets the current file content, applies changes,
     * and commits the result.
     *
     * @param repoFullName the full repository name
     * @param prNumber     the PR number (used to get the head branch)
     * @param patch        the unified diff patch
     * @return the commit SHA
     */
    public String pushPatchAsPRComment(String repoFullName, int prNumber, String patch) {
        log.info("Applying patch as commit on {}/pull/{}", repoFullName, prNumber);

        // Get the PR head branch
        String branch = getPRHeadBranch(repoFullName, prNumber);
        if (branch == null) {
            throw new RuntimeException("Could not determine PR head branch for " + repoFullName + "#" + prNumber);
        }

        // Parse the patch to extract file path and the new content
        // For MVP, we handle the first file in the patch
        String filePath = extractFilePathFromPatch(patch);
        if (filePath == null) {
            // Fallback: post as comment if we can't parse the patch
            log.warn("Could not parse file path from patch, posting as comment instead");
            return postPatchAsComment(repoFullName, prNumber, patch);
        }

        // Get the current file content from the PR branch
        String currentContent = getFileContent(repoFullName, filePath, branch);
        if (currentContent == null) {
            currentContent = "";
        }

        // Apply the patch to get new content
        String newContent = applyPatch(currentContent, patch);
        if (newContent == null) {
            // If patch application fails, post as comment
            log.warn("Could not apply patch programmatically, posting as comment");
            return postPatchAsComment(repoFullName, prNumber, patch);
        }

        // Push the new content as a commit
        String commitSha = pushCommit(repoFullName, branch, filePath, newContent,
                "fix: apply suggested changes from Agentic Workflows");
        log.info("Committed fix to {}/{} on branch {}. SHA: {}", repoFullName, filePath, branch, commitSha);
        return commitSha;
    }

    /**
     * Gets the head branch name of a PR.
     */
    private String getPRHeadBranch(String repoFullName, int prNumber) {
        try {
            String response = webClient.get()
                    .uri("/repos/" + repoFullName + "/pulls/" + prNumber)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            JsonNode root = objectMapper.readTree(response);
            return root.path("head").path("ref").asText(null);
        } catch (Exception e) {
            log.error("Failed to get PR head branch: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Gets the content of a file from a specific branch.
     */
    private String getFileContent(String repoFullName, String filePath, String branch) {
        try {
            String response = webClient.get()
                    .uri("/repos/" + repoFullName + "/contents/" + filePath + "?ref=" + branch)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, r -> Mono.empty())
                    .bodyToMono(String.class)
                    .block();
            if (response == null) return null;

            JsonNode root = objectMapper.readTree(response);
            String content = root.path("content").asText("");
            // GitHub returns base64-encoded content with newlines
            String cleaned = content.replaceAll("\\n", "").replaceAll("\\r", "");
            return new String(Base64.getDecoder().decode(cleaned), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("File not found or error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extracts the target file path from a unified diff patch.
     * Looks for "+++ b/path/to/file" lines.
     */
    String extractFilePathFromPatch(String patch) {
        if (patch == null) return null;
        for (String line : patch.split("\n")) {
            if (line.startsWith("+++ b/")) {
                return line.substring(6).trim();
            }
            if (line.startsWith("+++ ") && !line.startsWith("+++ /dev/null")) {
                String path = line.substring(4).trim();
                if (path.startsWith("b/")) path = path.substring(2);
                return path;
            }
        }
        return null;
    }

    /**
     * Simple patch application: takes the new lines from the patch (lines starting with +)
     * and constructs the new file content.
     * For a more robust solution, a proper diff library would be needed.
     */
    String applyPatch(String currentContent, String patch) {
        try {
            // Simple strategy: extract the "after" state from the diff
            // This works for new files or when the patch represents the complete new content
            StringBuilder newContent = new StringBuilder();
            String[] lines = patch.split("\n");
            boolean inHunk = false;

            // For new files (--- /dev/null), just take all + lines
            boolean isNewFile = false;
            for (String line : lines) {
                if (line.startsWith("--- /dev/null")) {
                    isNewFile = true;
                    break;
                }
            }

            if (isNewFile) {
                for (String line : lines) {
                    if (line.startsWith("+") && !line.startsWith("+++")) {
                        newContent.append(line.substring(1)).append("\n");
                    }
                }
                return newContent.toString();
            }

            // For modifications, reconstruct the file:
            // Keep context lines and + lines, skip - lines
            String[] currentLines = currentContent.split("\n", -1);
            List<String> result = new ArrayList<>(Arrays.asList(currentLines));

            // Parse hunks and apply changes
            int currentLineIndex = 0;
            int offset = 0;

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.startsWith("@@")) {
                    // Parse hunk header: @@ -startOld,countOld +startNew,countNew @@
                    String[] parts = line.split(" ");
                    if (parts.length >= 3) {
                        String oldRange = parts[1]; // -startOld,countOld
                        int startOld = Integer.parseInt(oldRange.substring(1).split(",")[0]);
                        currentLineIndex = startOld - 1 + offset;
                    }
                    inHunk = true;
                    continue;
                }

                if (!inHunk) continue;

                if (line.startsWith("-")) {
                    // Remove this line
                    if (currentLineIndex < result.size()) {
                        result.remove(currentLineIndex);
                        offset--;
                    }
                } else if (line.startsWith("+")) {
                    // Add this line
                    result.add(currentLineIndex, line.substring(1));
                    currentLineIndex++;
                    offset++;
                } else if (line.startsWith(" ") || line.isEmpty()) {
                    // Context line, move forward
                    currentLineIndex++;
                }
            }

            return String.join("\n", result);
        } catch (Exception e) {
            log.error("Failed to apply patch: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fallback: posts the patch as a PR comment when direct commit fails.
     */
    private String postPatchAsComment(String repoFullName, int prNumber, String patch) {
        String commentBody = "## \uD83E\uDD16 Agentic Workflows - Suggested Fix\n\n" +
                "```diff\n" + patch + "\n```\n\n" +
                "_Apply with: `git apply`_";

        Map<String, Object> requestBody = Map.of("body", commentBody);

        String responseBody = webClient.post()
                .uri("/repos/" + repoFullName + "/issues/" + prNumber + "/comments")
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(HttpStatusCode::isError,
                        response -> response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new RuntimeException(
                                        "GitHub API error posting PR comment: " + body))))
                .bodyToMono(String.class)
                .block();

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return "comment:" + root.path("html_url").asText("");
        } catch (Exception e) {
            return "comment_posted";
        }
    }

    /**
     * Gets the commit message for a given SHA.
     * Used to detect if a commit was made by the platform itself.
     */
    public String getCommitMessage(String repoFullName, String sha) {
        try {
            String response = webClient.get()
                    .uri("/repos/" + repoFullName + "/commits/" + sha)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r -> Mono.empty())
                    .bodyToMono(String.class)
                    .block();
            if (response == null) return null;
            JsonNode root = objectMapper.readTree(response);
            return root.path("commit").path("message").asText(null);
        } catch (Exception e) {
            return null;
        }
    }
}
