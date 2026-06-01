package com.agentic.service;

import com.agentic.dto.GeminiRequest;
import com.agentic.dto.GeminiResponse;
import com.agentic.exception.QuotaExhaustedException;
import com.agentic.exception.RateLimitException;
import com.agentic.model.GeminiUsage;
import com.agentic.repository.GeminiUsageRepository;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * LangChain4j-based Gemini service.
 * This replaces the raw WebClient-based GeminiService with a cleaner LLM abstraction.
 *
 * Benefits over raw HTTP:
 * - Type-safe API (no manual JSON construction/parsing)
 * - Built-in retry, streaming, tool calling support
 * - Swap to OpenAI/Anthropic/Ollama by just changing the model builder
 * - Structured outputs support
 *
 * To switch providers, just change GoogleAiGeminiChatModel to:
 * - OpenAiChatModel.builder()...  (for OpenAI)
 * - AnthropicChatModel.builder()... (for Claude)
 * - OllamaChatModel.builder()... (for local Ollama)
 */
@Service("langChainGeminiService")
@Slf4j
public class LangChainGeminiService {

    private final GeminiUsageRepository usageRepo;

    @Value("${gemini.api-key}")
    private String apiKey;

    @Value("${gemini.daily-limit}")
    private int dailyLimit;

    public LangChainGeminiService(GeminiUsageRepository usageRepo) {
        this.usageRepo = usageRepo;
    }

    /**
     * Generates a response using LangChain4j's ChatModel abstraction.
     * Handles rate limiting and usage tracking the same as the raw service.
     */
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

        // Build the model for this request (could also be cached per model name)
        ChatModel model = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(request.getModel() != null ? request.getModel() : "gemini-2.5-flash")
                .temperature(request.getTemperature())
                .maxOutputTokens(request.getMaxTokens())
                .timeout(Duration.ofSeconds(60))
                .maxRetries(1) // We handle retries at our level
                .build();

        // Build messages
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            messages.add(SystemMessage.from(request.getSystemPrompt()));
        }
        messages.add(UserMessage.from(request.getUserPrompt()));

        // Call the model
        String responseText;
        try {
            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(messages)
                    .build();
            ChatResponse chatResponse = model.chat(chatRequest);
            responseText = chatResponse.aiMessage().text();
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("429")) {
                throw new RateLimitException("Gemini API rate limit exceeded (429)");
            }
            throw new RuntimeException("Failed to call Gemini via LangChain4j: " + e.getMessage(), e);
        }

        // Track usage (LangChain4j doesn't easily expose token counts for all providers,
        // so we estimate based on response length)
        int estimatedTokens = (request.getUserPrompt().length() + responseText.length()) / 4;
        incrementUsage(usage, estimatedTokens);

        return GeminiResponse.builder()
                .content(responseText)
                .promptTokens(request.getUserPrompt().length() / 4)
                .completionTokens(responseText.length() / 4)
                .totalTokens(estimatedTokens)
                .build();
    }

    private GeminiUsage getTodayUsage() {
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
}
