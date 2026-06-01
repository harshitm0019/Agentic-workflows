package com.agentic.service;

import com.agentic.dto.GeminiRequest;
import com.agentic.dto.GeminiResponse;
import com.agentic.exception.QuotaExhaustedException;
import com.agentic.exception.RateLimitException;
import com.agentic.model.GeminiUsage;
import com.agentic.repository.GeminiUsageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * ORIGINAL raw WebClient-based Gemini service.
 * 
 * NOTE: This is the original implementation that calls Gemini REST API directly using Spring WebClient.
 * It works but requires manual JSON construction, response parsing, and error handling.
 * 
 * A cleaner alternative using LangChain4j is available: LangChainGeminiService.java
 * To switch, change the bean qualifier in AgentBeansConfig or mark this as @Primary.
 * 
 * This class is kept as reference to show how raw LLM API integration works
 * without a framework. Both approaches are valid:
 * - Raw WebClient: Full control, no extra dependencies, more boilerplate
 * - LangChain4j: Cleaner API, swap providers easily, less code
 */
@Service
@Slf4j
public class GeminiService {

    private final WebClient webClient;
    private final GeminiUsageRepository usageRepo;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api-key}")
    private String apiKey;

    @Value("${gemini.daily-limit}")
    private int dailyLimit;

    public GeminiService(WebClient.Builder webClientBuilder,
                         GeminiUsageRepository usageRepo,
                         ObjectMapper objectMapper,
                         @Value("${gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl) {
        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .build();
        this.usageRepo = usageRepo;
        this.objectMapper = objectMapper;
    }

    @Retryable(retryFor = RateLimitException.class, maxAttempts = 3,
               backoff = @Backoff(delay = 10000, multiplier = 2))
    @Transactional
    public GeminiResponse generate(GeminiRequest request) {
        GeminiUsage usage = getTodayUsage();

        if (usage.getRequestCount() >= dailyLimit) {
            throw new QuotaExhaustedException("Daily Gemini quota reached");
        }

        if (usage.getRequestCount() >= dailyLimit * 0.8) {
            log.warn("Gemini usage at 80% of daily limit: {}/{}", usage.getRequestCount(), dailyLimit);
        }

        GeminiResponse response = callGeminiApi(request);
        incrementUsage(usage, response.getTotalTokens());
        return response;
    }

    GeminiUsage getTodayUsage() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return usageRepo.findByDate(today)
                .orElseGet(() -> {
                    GeminiUsage newUsage = GeminiUsage.builder()
                            .date(today)
                            .requestCount(0)
                            .totalTokens(0)
                            .updatedAt(Instant.now())
                            .build();
                    return usageRepo.save(newUsage);
                });
    }

    private void incrementUsage(GeminiUsage usage, int tokensUsed) {
        usage.setRequestCount(usage.getRequestCount() + 1);
        usage.setTotalTokens(usage.getTotalTokens() + tokensUsed);
        usage.setUpdatedAt(Instant.now());
        usageRepo.save(usage);
    }

    GeminiResponse callGeminiApi(GeminiRequest request) {
        String model = request.getModel() != null ? request.getModel() : "gemini-1.5-flash";
        String url = String.format("/v1beta/models/%s:generateContent?key=%s", model, apiKey);

        Map<String, Object> requestBody = buildRequestBody(request);

        String responseBody = webClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(status -> status.value() == 429,
                        clientResponse -> Mono.error(new RateLimitException("Gemini API rate limit exceeded (429)")))
                .onStatus(HttpStatusCode::isError,
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new RuntimeException("Gemini API error: " + body))))
                .bodyToMono(String.class)
                .block();

        return parseResponse(responseBody);
    }

    private Map<String, Object> buildRequestBody(GeminiRequest request) {
        Map<String, Object> generationConfig = Map.of(
                "temperature", request.getTemperature(),
                "maxOutputTokens", request.getMaxTokens()
        );

        Map<String, Object> userPart = Map.of("text", request.getUserPrompt());
        Map<String, Object> userContent = Map.of(
                "role", "user",
                "parts", List.of(userPart)
        );

        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            Map<String, Object> systemInstruction = Map.of(
                    "parts", List.of(Map.of("text", request.getSystemPrompt()))
            );
            return Map.of(
                    "contents", List.of(userContent),
                    "systemInstruction", systemInstruction,
                    "generationConfig", generationConfig
            );
        }

        return Map.of(
                "contents", List.of(userContent),
                "generationConfig", generationConfig
        );
    }

    GeminiResponse parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode candidates = root.path("candidates");

            if (candidates.isEmpty() || candidates.isMissingNode()) {
                return GeminiResponse.builder()
                        .content("")
                        .promptTokens(0)
                        .completionTokens(0)
                        .totalTokens(0)
                        .build();
            }

            JsonNode firstCandidate = candidates.get(0);
            JsonNode contentNode = firstCandidate.path("content").path("parts");
            StringBuilder text = new StringBuilder();
            if (contentNode.isArray()) {
                for (JsonNode part : contentNode) {
                    text.append(part.path("text").asText(""));
                }
            }

            JsonNode usageMetadata = root.path("usageMetadata");
            int promptTokens = usageMetadata.path("promptTokenCount").asInt(0);
            int completionTokens = usageMetadata.path("candidatesTokenCount").asInt(0);
            int totalTokens = usageMetadata.path("totalTokenCount").asInt(0);

            return GeminiResponse.builder()
                    .content(text.toString())
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .totalTokens(totalTokens)
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse Gemini response: {}", responseBody, e);
            return GeminiResponse.builder()
                    .content("")
                    .promptTokens(0)
                    .completionTokens(0)
                    .totalTokens(0)
                    .build();
        }
    }
}
