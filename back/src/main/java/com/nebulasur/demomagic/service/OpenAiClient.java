package com.nebulasur.demomagic.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class OpenAiClient {

    private static final String OPENAI_API = "https://api.openai.com/v1";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String chatModel;
    private final double chatTemperature;
    private final String embeddingModel;

    public OpenAiClient(
        ObjectMapper objectMapper,
        @Value("${OPENAI_API_KEY:}") String apiKey,
        @Value("${openai.chat.model:gpt-4o-mini}") String chatModel,
        @Value("${openai.chat.temperature:0}") double chatTemperature,
        @Value("${openai.embedding.model:text-embedding-3-small}") String embeddingModel
    ) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.chatModel = chatModel;
        this.chatTemperature = Math.max(0.0, Math.min(2.0, chatTemperature));
        this.embeddingModel = embeddingModel;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public Optional<List<Double>> embed(String input) {
        if (!isConfigured() || input == null || input.isBlank()) {
            return Optional.empty();
        }

        try {
            String payload = objectMapper.createObjectNode()
                .put("model", embeddingModel)
                .put("input", input)
                .toString();

            HttpRequest request = HttpRequest.newBuilder(URI.create(OPENAI_API + "/embeddings"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode vectorNode = root.path("data").path(0).path("embedding");
            if (!vectorNode.isArray()) {
                return Optional.empty();
            }

            List<Double> vector = new ArrayList<>();
            vectorNode.forEach(value -> vector.add(value.asDouble()));
            return Optional.of(vector);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public Optional<String> complete(String systemPrompt, String userPrompt) {
        if (!isConfigured()) {
            return Optional.empty();
        }

        try {
            JsonNode payload = objectMapper.createObjectNode()
                .put("model", chatModel)
                .put("temperature", chatTemperature)
                .set("messages", objectMapper.createArrayNode()
                    .add(objectMapper.createObjectNode()
                        .put("role", "system")
                        .put("content", systemPrompt))
                    .add(objectMapper.createObjectNode()
                        .put("role", "user")
                        .put("content", userPrompt))
                );

            HttpRequest request = HttpRequest.newBuilder(URI.create(OPENAI_API + "/chat/completions"))
                .timeout(Duration.ofSeconds(40))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (content == null || content.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(content.trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
