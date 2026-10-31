package com.kagtro.rag.ai;

import java.util.function.Consumer;

/**
 * 大模型聊天接口（只做"输入提示词、返回文本"这一件事）。
 * 业务层不关心底层是 qwen、DeepSeek 还是本地模型，方便以后替换。
 */
public interface ChatClient {

    /**
     * 非流式：一次性返回完整回答。
     */
    String chat(String systemPrompt, String userPrompt);

    /**
     * 流式：模型每生成一小段文本，就回调一次 onDelta。
     * 上层负责把这些小段推给浏览器（SSE）。
     *
     * @param onDelta 每收到一段文字就调用一次
     */
    void chatStream(String systemPrompt, String userPrompt, Consumer<String> onDelta);
}
