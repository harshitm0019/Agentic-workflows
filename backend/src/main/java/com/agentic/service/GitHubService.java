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

        try {
            String downloadUrl = webClient.get()
                    .uri("/repos/" + repoFullName + "/actions/runs/" + runId + "/logs")
                    .exchangeToMono(response -> {
                        int statusCode = response.statusCode().value();
                        if (statusCode == 302) {
                            String location = response.headers().asHttpHeaders().getFirst("Location");
                            return Mono.justOrEmpty(location);
                        } else if (statusCode == 401) {
                            return Mono.error(new GitHubAuthenticationException(
                                    "GitHub authentication failed for workflow logs: " + repoFullName + "/runs/" + runId));
                        } else if (statusCode == 403) {
                            return Mono.error(new GitHubForbiddenException(
                                    "Access forbidden to workflow logs: " + repoFullName + "/runs/" + runId));
                        } else if (statusCode == 404) {
                            return Mono.error(new GitHubNotFoundException(
                                    "Workflow run not found: " + repoFullName + "/runs/" + runId));
                        } else if (response.statusCode().is2xxSuccessful()) {
                            return response.bodyToMono(byte[].class)
                                    .map(bytes -> "DIRECT:" + Base64.getEncoder().encodeToString(bytes));
                        } else {
                            return response.bodyToMono(String.class)
                                    .flatMap(body -> Mono.error(new RuntimeException(
                                            "GitHub API error fetching logs: " + response.statusCode() + " " + body)));
                        }
                    })
                    .block();

            if (downloadUrl == null || downloadUrl.isEmpty()) {
                log.warn("No logs download URL returned for run {}", runId);
                return "";
            }

            byte[] zipBytes;
            if (downloadUrl.startsWith("DIRECT:")) {
                zipBytes = Base64.getDecoder().decode(downloadUrl.substring(7));
            } else {
                zipBytes = WebClient.create()
                        .get()
                        .uri(downloadUrl)
                        .retrieve()
                        .bodyToMono(byte[].class)
                        .block();
            }

            String logs = extractLogsFromZip(zipBytes);
            log.info("Fetched {} chars of logs for run {}", logs.length(), runId);
            return logs;
        } catch (GitHubAuthenticationException | GitHubForbiddenException | GitHubNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch workflow logs for run {}: {}", runId, e.getMessage());
            return "";
        }
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

        List<Map.Entry<String, String>> entries = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    byte[] entryBytes = zis.readAllBytes();
                    String entryContent = new String(entryBytes, StandardCharsets.UTF_8);
                    entries.add(Map.entry(entry.getName(), entryContent));
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            log.error("Failed to extract logs from zip", e);
        }

        // Separate entries with error indicators from noise
        List<Map.Entry<String, String>> errorEntries = new ArrayList<>();
        List<Map.Entry<String, String>> otherEntries = new ArrayList<>();

        for (Map.Entry<String, String> e : entries) {
            String content = e.getValue();
            if (containsErrorSignals(content)) {
                errorEntries.add(e);
            } else {
                otherEntries.add(e);
            }
        }

        // Build output: error entries first, then others if space remains
        StringBuilder logs = new StringBuilder();
        for (Map.Entry<String, String> e : errorEntries) {
            logs.append("=== ").append(e.getKey()).append(" ===\n");
            logs.append(e.getValue()).append("\n");
            if (logs.length() >= MAX_LOG_CHARS) break;
        }
        for (Map.Entry<String, String> e : otherEntries) {
            if (logs.length() >= MAX_LOG_CHARS) break;
            logs.append("=== ").append(e.getKey()).append(" ===\n");
            logs.append(e.getValue()).append("\n");
        }

        if (logs.length() > MAX_LOG_CHARS) {
            return logs.substring(0, MAX_LOG_CHARS);
        }
        return logs.toString();
    }

    private boolean containsErrorSignals(String content) {
        return content.contains("FAILED") || content.contains("Failures:")
                || content.contains("BUILD FAILURE") || content.contains("Error:")
                || content.contains("Exception") || content.contains("AssertionError")
                || content.contains("Process completed with exit code 1");
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

        String branch = getPRHeadBranch(repoFullName, prNumber);
        if (branch == null) {
            throw new RuntimeException("Could not determine PR head branch for " + repoFullName + "#" + prNumber);
        }

        Map<String, String> fileDiffs = splitPatchByFile(patch);
        if (fileDiffs.isEmpty()) {
            log.warn("Could not parse any file paths from patch, posting as comment instead");
            return postPatchAsComment(repoFullName, prNumber, patch);
        }

        String lastCommitSha = null;
        for (Map.Entry<String, String> entry : fileDiffs.entrySet()) {
            String filePath = entry.getKey();
            String fileDiff = entry.getValue();

            String currentContent = getFileContent(repoFullName, filePath, branch);
            if (currentContent == null) {
                currentContent = "";
            }

            String newContent = applyPatch(currentContent, fileDiff);
            if (newContent == null) {
                log.warn("Could not apply patch for file {}, posting as comment", filePath);
                return postPatchAsComment(repoFullName, prNumber, patch);
            }

            lastCommitSha = pushCommit(repoFullName, branch, filePath, newContent,
                    "fix: apply suggested changes from Agentic Workflows");
            log.info("Committed fix to {}/{} on branch {}. SHA: {}", repoFullName, filePath, branch, lastCommitSha);
        }

        return lastCommitSha;
    }

    Map<String, String> splitPatchByFile(String patch) {
        Map<String, String> fileDiffs = new LinkedHashMap<>();
        if (patch == null || patch.isBlank()) return fileDiffs;

        String[] lines = patch.split("\n");
        String currentFile = null;
        StringBuilder currentDiff = new StringBuilder();

        for (String line : lines) {
            if (line.startsWith("diff --git") || line.startsWith("+++ b/") || line.startsWith("+++ ")) {
                if (line.startsWith("+++ b/")) {
                    if (currentFile != null) {
                        fileDiffs.put(currentFile, currentDiff.toString());
                    }
                    currentFile = line.substring(6).trim();
                    currentDiff = new StringBuilder();
                    currentDiff.append(line).append("\n");
                } else if (line.startsWith("+++ ") && !line.startsWith("+++ /dev/null")) {
                    if (currentFile != null) {
                        fileDiffs.put(currentFile, currentDiff.toString());
                    }
                    String path = line.substring(4).trim();
                    if (path.startsWith("b/")) path = path.substring(2);
                    currentFile = path;
                    currentDiff = new StringBuilder();
                    currentDiff.append(line).append("\n");
                } else {
                    currentDiff.append(line).append("\n");
                }
            } else if (line.startsWith("--- ")) {
                currentDiff.append(line).append("\n");
            } else {
                currentDiff.append(line).append("\n");
            }
        }

        if (currentFile != null) {
            fileDiffs.put(currentFile, currentDiff.toString());
        }

        return fileDiffs;
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
            String[] lines = patch.split("\n");

            // For new files (--- /dev/null), just take all + lines
            boolean isNewFile = false;
            for (String line : lines) {
                if (line.startsWith("--- /dev/null")) {
                    isNewFile = true;
                    break;
                }
            }

            if (isNewFile) {
                StringBuilder newContent = new StringBuilder();
                for (String line : lines) {
                    if (line.startsWith("+") && !line.startsWith("+++")) {
                        newContent.append(line.substring(1)).append("\n");
                    }
                }
                return newContent.toString();
            }

            // For modifications: parse hunks and apply them sequentially
            String[] currentLines = currentContent.split("\n", -1);
            List<String> result = new ArrayList<>(Arrays.asList(currentLines));
            int offset = 0;

            // Parse all hunks
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (!line.startsWith("@@")) continue;

                // Parse hunk header: @@ -startOld,countOld +startNew,countNew @@
                int startOld = parseHunkStart(line);
                if (startOld < 0) continue;

                int resultIndex = startOld - 1 + offset;

                // Process hunk lines
                i++;
                while (i < lines.length && !lines[i].startsWith("@@") && !lines[i].startsWith("diff ")) {
                    String hunkLine = lines[i];
                    if (hunkLine.startsWith("-")) {
                        // Remove: verify the line matches before removing
                        if (resultIndex < result.size()) {
                            String expected = hunkLine.substring(1);
                            String actual = result.get(resultIndex);
                            if (actual.trim().equals(expected.trim())) {
                                result.remove(resultIndex);
                                offset--;
                            } else {
                                log.warn("Patch context mismatch at line {}: expected '{}', got '{}'",
                                        resultIndex + 1, expected.trim(), actual.trim());
                                return null;
                            }
                        }
                    } else if (hunkLine.startsWith("+")) {
                        // Add line
                        result.add(resultIndex, hunkLine.substring(1));
                        resultIndex++;
                        offset++;
                    } else {
                        // Context line — verify it matches and advance
                        String contextLine = hunkLine.startsWith(" ") ? hunkLine.substring(1) : hunkLine;
                        if (resultIndex < result.size()) {
                            String actual = result.get(resultIndex);
                            if (!actual.trim().equals(contextLine.trim())) {
                                log.warn("Patch context mismatch at line {}: expected '{}', got '{}'",
                                        resultIndex + 1, contextLine.trim(), actual.trim());
                                return null;
                            }
                        }
                        resultIndex++;
                    }
                    i++;
                }
                i--; // Back up since the outer loop will increment
            }

            return String.join("\n", result);
        } catch (Exception e) {
            log.error("Failed to apply patch: {}", e.getMessage());
            return null;
        }
    }

    private int parseHunkStart(String hunkHeader) {
        try {
            // @@ -startOld,countOld +startNew,countNew @@
            String[] parts = hunkHeader.split(" ");
            if (parts.length >= 3) {
                String oldRange = parts[1]; // -startOld,countOld
                return Integer.parseInt(oldRange.substring(1).split(",")[0]);
            }
        } catch (NumberFormatException e) {
            log.warn("Failed to parse hunk header: {}", hunkHeader);
        }
        return -1;
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
