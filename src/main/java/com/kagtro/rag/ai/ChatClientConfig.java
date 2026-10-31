package com.kagtro.rag.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatClientConfig.class);

    @Bean
    public ChatClient chatClient(AiProperties properties) {
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("未配置 DASHSCOPE_API_KEY，使用 MockChatClient（回答是假的，仅用于跑通链路）");
            return new MockChatClient();
        }
        log.info("使用 DashScopeChatClient，模型={}", properties.getChatModel());
        return new DashScopeChatClient(properties);
    }
}
