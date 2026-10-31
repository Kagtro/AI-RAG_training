package com.kagtro.rag.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 阿里云百炼 OpenAI 兼容 Chat Completions 实现。
 * 同时支持：非流式（chat）和流式（chatStream）。
 */
public class DashScopeChatClient implements ChatClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeChatClient.class);
    private static final int MAX_RETRY = 3;

    /** 流式请求专用的 JDK HttpClient */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final AiProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DashScopeChatClient(AiProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String chat(String systemPrompt, String userPrompt) {
        Map<String, Object> body = buildBody(systemPrompt, userPrompt, false);

        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                Map<String, Object> response = restClient.post()
                        .uri("/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(Map.class);

                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
                if (choices == null || choices.isEmpty()) {
                    throw new IllegalStateException("模型没有返回 choices");
                }
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                return String.valueOf(message.get("content"));
            } catch (Exception e) {
                last = new RuntimeException("大模型调用失败(第 " + attempt + " 次): " + e.getMessage(), e);
                log.warn(last.getMessage());
                sleepQuietly(800L * attempt);
            }
        }
        throw last == null ? new IllegalStateException("大模型调用失败") : last;
    }

    /**
     * 流式实现：
     * 1. 请求体里 stream=true；
     * 2. 服务端返回 text/event-stream，逐行读取 "data: {...}"；
     * 3. 每行解析出 choices[0].delta.content，回调 onDelta；
     * 4. 读到 "data: [DONE]" 结束。
     */
    @Override
    public void chatStream(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        Map<String, Object> body = buildBody(systemPrompt, userPrompt, true);
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBaseUrl() + "/chat/completions"))
                    .timeout(Duration.ofMinutes(2))
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                String error = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException("流式调用失败，HTTP " + response.statusCode() + ": " + error);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || !line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring("data:".length()).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JsonNode node = objectMapper.readTree(data);
                    JsonNode content = node.path("choices").path(0).path("delta").path("content");
                    if (!content.isMissingNode() && !content.isNull()) {
                        String text = content.asText();
                        if (!text.isEmpty()) {
                            onDelta.accept(text);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("流式调用失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildBody(String systemPrompt, String userPrompt, boolean stream) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", properties.getChatModel());
        body.put("temperature", properties.getTemperature());
        body.put("max_tokens", properties.getMaxTokens());
        body.put("stream", stream);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        return body;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
