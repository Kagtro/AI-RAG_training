package com.kagtro.rag.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kagtro.rag.ai.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 阿里云百炼 OpenAI 兼容 Embedding 接口。
 * POST {baseUrl}/embeddings
 */
public class DashScopeEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeEmbeddingClient.class);
    private static final int BATCH_SIZE = 10;
    private static final int MAX_RETRY = 3;

    private final AiProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DashScopeEmbeddingClient(AiProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .build();
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> result = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += BATCH_SIZE) {
            int to = Math.min(texts.size(), from + BATCH_SIZE);
            result.addAll(embedBatch(texts.subList(from, to)));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<float[]> embedBatch(List<String> texts) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", properties.getEmbeddingModel());
        body.put("input", texts);
        body.put("dimensions", properties.getEmbeddingDimension());
        body.put("encoding_format", "float");

        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                Map<String, Object> response = restClient.post()
                        .uri("/embeddings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(Map.class);

                List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
                if (data == null || data.size() != texts.size()) {
                    throw new IllegalStateException("Embedding 返回数量不匹配: " + (data == null ? 0 : data.size()));
                }
                List<float[]> vectors = new ArrayList<>(data.size());
                for (Map<String, Object> item : data) {
                    List<Number> embedding = (List<Number>) item.get("embedding");
                    float[] vec = new float[embedding.size()];
                    for (int i = 0; i < embedding.size(); i++) {
                        vec[i] = embedding.get(i).floatValue();
                    }
                    vectors.add(vec);
                }
                return vectors;
            } catch (Exception e) {
                last = new RuntimeException("Embedding 调用失败(第 " + attempt + " 次): " + e.getMessage(), e);
                log.warn(last.getMessage());
                try {
                    Thread.sleep(800L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last == null ? new IllegalStateException("Embedding 调用失败") : last;
    }

    @Override
    public int dimension() {
        return properties.getEmbeddingDimension();
    }
}
