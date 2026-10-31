package com.kagtro.rag.ai;

import java.util.function.Consumer;

/**
 * 没有 API Key 时的兜底实现：返回固定文本，保证链路能跑通。
 * 注意：它不是真实回答，只用于本地开发/演示。
 */
public class MockChatClient implements ChatClient {

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        return "【Mock 回答】没有配置大模型 API Key，这是一条假回答。";
    }

    @Override
    public void chatStream(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        String text = "【Mock 流式回答】没有配置大模型 API Key，这是一条假回答。";
        for (String part : text.split("")) {
            onDelta.accept(part);
        }
    }
}
