package com.kagtro.rag.embedding;

import com.kagtro.rag.ai.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    @Bean
    public EmbeddingClient embeddingClient(AiProperties properties) {
        String apiKey = properties.getApiKey();
        int dimension = properties.getEmbeddingDimension();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("未配置 DASHSCOPE_API_KEY，使用 MockEmbeddingClient（仅用于本地跑通链路，不具备真实语义检索能力）");
            return new MockEmbeddingClient(dimension);
        }
        log.info("使用 DashScopeEmbeddingClient，模型={}，维度={}", properties.getEmbeddingModel(), dimension);
        return new DashScopeEmbeddingClient(properties);
    }
}
