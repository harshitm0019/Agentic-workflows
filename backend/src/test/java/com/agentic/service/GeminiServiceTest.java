package com.agentic.service;

import com.agentic.dto.GeminiRequest;
import com.agentic.dto.GeminiResponse;
import com.agentic.exception.QuotaExhaustedException;
import com.agentic.exception.RateLimitException;
import com.agentic.model.GeminiUsage;
import com.agentic.repository.GeminiUsageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GeminiServiceTest {

    private MockWebServer mockWebServer;
    private GeminiService geminiService;

    @Mock
    private GeminiUsageRepository usageRepo;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        String baseUrl = mockWebServer.url("/").toString();
        WebClient.Builder webClientBuilder = WebClient.builder();

        geminiService = new GeminiService(webClientBuilder, usageRepo, objectMapper, baseUrl);
        ReflectionTestUtils.setField(geminiService, "apiKey", "test-api-key");
        ReflectionTestUtils.setField(geminiService, "dailyLimit", 1500);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void generate_successfulCall_returnsContentAndUpdatesUsage() {
        // Arrange
        GeminiUsage usage = buildUsage(10, 500);
        when(usageRepo.findByDate(LocalDate.now(ZoneOffset.UTC))).thenReturn(Optional.of(usage));
        when(usageRepo.save(any(GeminiUsage.class))).thenReturn(usage);

        String geminiResponseJson = """
                {
                    "candidates": [{
                        "content": {
                            "parts": [{"text": "Hello, world!"}],
                            "role": "model"
                        }
                    }],
                    "usageMetadata": {
                        "promptTokenCount": 10,
                        "candidatesTokenCount": 5,
                        "totalTokenCount": 15
                    }
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(geminiResponseJson)
                .addHeader("Content-Type", "application/json"));

        GeminiRequest request = GeminiRequest.builder()
                .model("gemini-1.5-flash")
                .userPrompt("Say hello")
                .temperature(0.7)
                .maxTokens(100)
                .build();

        // Act
        GeminiResponse response = geminiService.generate(request);

        // Assert
        assertThat(response.getContent()).isEqualTo("Hello, world!");
        assertThat(response.getPromptTokens()).isEqualTo(10);
        assertThat(response.getCompletionTokens()).isEqualTo(5);
        assertThat(response.getTotalTokens()).isEqualTo(15);

        verify(usageRepo).save(any(GeminiUsage.class));
        assertThat(usage.getRequestCount()).isEqualTo(11);
        assertThat(usage.getTotalTokens()).isEqualTo(515);
    }

    @Test
    void generate_withSystemPrompt_includesSystemInstruction() {
        // Arrange
        GeminiUsage usage = buildUsage(0, 0);
        when(usageRepo.findByDate(LocalDate.now(ZoneOffset.UTC))).thenReturn(Optional.of(usage));
        when(usageRepo.save(any(GeminiUsage.class))).thenReturn(usage);

        String geminiResponseJson = """
                {
                    "candidates": [{
                        "content": {
                            "parts": [{"text": "Code review response"}],
                            "role": "model"
                        }
                    }],
                    "usageMetadata": {
                        "promptTokenCount": 20,
                        "candidatesTokenCount": 10,
                        "totalTokenCount": 30
                    }
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(geminiResponseJson)
                .addHeader("Content-Type", "application/json"));

        GeminiRequest request = GeminiRequest.builder()
                .model("gemini-1.5-flash")
                .systemPrompt("You are a code reviewer.")
                .userPrompt("Review this code")
                .temperature(0.3)
                .maxTokens(2048)
                .build();

        // Act
        GeminiResponse response = geminiService.generate(request);

        // Assert
        assertThat(response.getContent()).isEqualTo("Code review response");
    }

    @Test
    void generate_atQuotaLimit_throwsQuotaExhaustedException() {
        // Arrange
        GeminiUsage usage = buildUsage(1500, 50000);
        when(usageRepo.findByDate(LocalDate.now(ZoneOffset.UTC))).thenReturn(Optional.of(usage));

        GeminiRequest request = GeminiRequest.builder()
                .model("gemini-1.5-flash")
                .userPrompt("Test prompt")
                .build();

        // Act & Assert
        assertThatThrownBy(() -> geminiService.generate(request))
                .isInstanceOf(QuotaExhaustedException.class)
                .hasMessageContaining("Daily Gemini quota reached");

        verify(usageRepo, never()).save(any());
    }

    @Test
    void generate_at80PercentUsage_logsWarningButProceeds() {
        // Arrange - 80% of 1500 = 1200
        GeminiUsage usage = buildUsage(1200, 40000);
        when(usageRepo.findByDate(LocalDate.now(ZoneOffset.UTC))).thenReturn(Optional.of(usage));
        when(usageRepo.save(any(GeminiUsage.class))).thenReturn(usage);

        String geminiResponseJson = """
                {
                    "candidates": [{
                        "content": {
                            "parts": [{"text": "Response at 80%"}],
                            "role": "model"
                        }
                    }],
                    "usageMetadata": {
                        "promptTokenCount": 5,
                        "candidatesTokenCount": 3,
                        "totalTokenCount": 8
                    }
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(geminiResponseJson)
                .addHeader("Content-Type", "application/json"));

        GeminiRequest request = GeminiRequest.builder()
                .model("gemini-1.5-flash")
                .userPrompt("Test at 80%")
                .build();

        // Act
        GeminiResponse response = geminiService.generate(request);

        // Assert - should still return content (warning is logged)
        assertThat(response.getContent()).isEqualTo("Response at 80%");
        verify(usageRepo).save(any(GeminiUsage.class));
    }

    @Test
    void generate_on429Error_throwsRateLimitException() {
        // Arrange
        GeminiUsage usage = buildUsage(10, 500);
        when(usageRepo.findByDate(LocalDate.now(ZoneOffset.UTC))).thenReturn(Optional.of(usage));

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(429)
                .setBody("{\"error\": \"Rate limit exceeded\"}")
                .addHeader("Content-Type", "application/json"));

        GeminiRequest request = GeminiRequest.builder()
                .model("gemini-1.5-flash")
                .userPrompt("Test rate limit")
                .build();

        // Act & Assert
        assertThatThrownBy(() -> geminiService.generate(request))
                .isInstanceOf(RateLimitException.class)
                .hasMessageContaining("rate limit");
    }

    @Test
    void generate_malformedResponse_returnsEmptyContent() {
        // Arrange
        GeminiUsage usage = buildUsage(10, 500);
        when(usageRepo.findByDate(LocalDate.now(ZoneOffset.UTC))).thenReturn(Optional.of(usage));
        when(usageRepo.save(any(GeminiUsage.class))).thenReturn(usage);

        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"invalid\": \"response\"}")
                .addHeader("Content-Type", "application/json"));

        GeminiRequest request = GeminiRequest.builder()
                .model("gemini-1.5-flash")
                .userPrompt("Test malformed")
                .build();

        // Act
        GeminiResponse response = geminiService.generate(request);

        // Assert - graceful handling: empty content, zero tokens
        assertThat(response.getContent()).isEmpty();
        assertThat(response.getTotalTokens()).isZero();
    }

    @Test
    void generate_emptyCandidates_returnsEmptyContent() {
        // Arrange
        GeminiUsage usage = buildUsage(10, 500);
        when(usageRepo.findByDate(LocalDate.now(ZoneOffset.UTC))).thenReturn(Optional.of(usage));
        when(usageRepo.save(any(GeminiUsage.class))).thenReturn(usage);

        String geminiResponseJson = """
                {
                    "candidates": [],
                    "usageMetadata": {
                        "promptTokenCount": 5,
                        "candidatesTokenCount": 0,
                        "totalTokenCount": 5
                    }
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setBody(geminiResponseJson)
                .addHeader("Content-Type", "application/json"));

        GeminiRequest request = GeminiRequest.builder()
                .model("gemini-1.5-flash")
                .userPrompt("Test empty candidates")
                .build();

        // Act
        GeminiResponse response = geminiService.generate(request);

        // Assert
        assertThat(response.getContent()).isEmpty();
    }

    @Test
    void getTodayUsage_noExistingRecord_createsNewRecord() {
        // Arrange
        when(usageRepo.findByDate(LocalDate.now(ZoneOffset.UTC))).thenReturn(Optional.empty());
        GeminiUsage newUsage = buildUsage(0, 0);
        when(usageRepo.save(any(GeminiUsage.class))).thenReturn(newUsage);

        // Act
        GeminiUsage result = geminiService.getTodayUsage();

        // Assert
        assertThat(result.getRequestCount()).isZero();
        assertThat(result.getTotalTokens()).isZero();
        verify(usageRepo).save(any(GeminiUsage.class));
    }

    @Test
    void getTodayUsage_existingRecord_returnsExisting() {
        // Arrange
        GeminiUsage existing = buildUsage(42, 1234);
        when(usageRepo.findByDate(LocalDate.now(ZoneOffset.UTC))).thenReturn(Optional.of(existing));

        // Act
        GeminiUsage result = geminiService.getTodayUsage();

        // Assert
        assertThat(result.getRequestCount()).isEqualTo(42);
        assertThat(result.getTotalTokens()).isEqualTo(1234);
        verify(usageRepo, never()).save(any());
    }

    @Test
    void parseResponse_multipleParts_concatenatesText() {
        // Arrange
        String responseJson = """
                {
                    "candidates": [{
                        "content": {
                            "parts": [
                                {"text": "Part 1. "},
                                {"text": "Part 2."}
                            ],
                            "role": "model"
                        }
                    }],
                    "usageMetadata": {
                        "promptTokenCount": 10,
                        "candidatesTokenCount": 8,
                        "totalTokenCount": 18
                    }
                }
                """;

        // Act
        GeminiResponse response = geminiService.parseResponse(responseJson);

        // Assert
        assertThat(response.getContent()).isEqualTo("Part 1. Part 2.");
        assertThat(response.getTotalTokens()).isEqualTo(18);
    }

    @Test
    void generate_serverError_throwsRuntimeException() {
        // Arrange
        GeminiUsage usage = buildUsage(10, 500);
        when(usageRepo.findByDate(LocalDate.now(ZoneOffset.UTC))).thenReturn(Optional.of(usage));

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\": \"Internal server error\"}")
                .addHeader("Content-Type", "application/json"));

        GeminiRequest request = GeminiRequest.builder()
                .model("gemini-1.5-flash")
                .userPrompt("Test server error")
                .build();

        // Act & Assert
        assertThatThrownBy(() -> geminiService.generate(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Gemini API error");
    }

    private GeminiUsage buildUsage(int requestCount, int totalTokens) {
        return GeminiUsage.builder()
                .id(UUID.randomUUID())
                .date(LocalDate.now(ZoneOffset.UTC))
                .requestCount(requestCount)
                .totalTokens(totalTokens)
                .updatedAt(Instant.now())
                .build();
    }
}
