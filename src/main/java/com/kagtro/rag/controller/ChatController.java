package com.kagtro.rag.controller;

import com.kagtro.rag.service.ChatService;
import com.kagtro.rag.service.ChatService.ChatAnswer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executor;

/**
 * 问答接口：
 * - /api/chat        非流式（一次性返回，方便调试）
 * - /api/chat/stream 流式（SSE，前端用 EventSource 接收）
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final Executor chatExecutor;

    public ChatController(ChatService chatService,
                          @Qualifier("chatExecutor") Executor chatExecutor) {
        this.chatService = chatService;
        this.chatExecutor = chatExecutor;
    }

    /**
     * POST /api/chat
     * 请求体：{"kbId":1,"question":"报销上限是多少","topK":5,"minScore":0.1}
     */
    @PostMapping
    public ChatAnswer chat(@RequestBody ChatRequest request) {
        long kbId = request.kbId() == null ? 1L : request.kbId();
        return chatService.ask(kbId, request.question(), request.topK(), request.minScore());
    }

    /**
     * GET /api/chat?question=...&kbId=1
     * 非流式，方便 curl 调试。
     */
    @GetMapping
    public ChatAnswer chatGet(@RequestParam String question,
                              @RequestParam(defaultValue = "1") long kbId,
                              @RequestParam(required = false) Integer topK,
                              @RequestParam(required = false) Double minScore) {
        return chatService.ask(kbId, question, topK, minScore);
    }

    /**
     * GET /api/chat/stream?question=...&kbId=1
     * 流式：返回 text/event-stream，浏览器可用 EventSource 订阅。
     *
     * 关键点：
     * 1. 立刻返回 SseEmitter（连接保持打开）；
     * 2. 真正的生成逻辑丢到 chatExecutor 线程池里跑，避免阻塞 Tomcat 线程；
     * 3. 超时时间 5 分钟。
     */
    @GetMapping("/stream")
    public SseEmitter stream(@RequestParam String question,
                             @RequestParam(defaultValue = "1") long kbId,
                             @RequestParam(required = false) Integer topK,
                             @RequestParam(required = false) Double minScore) {
        SseEmitter emitter = new SseEmitter(300_000L);
        emitter.onTimeout(emitter::complete);
        chatExecutor.execute(() -> chatService.streamTo(kbId, question, topK, minScore, emitter));
        return emitter;
    }

    public record ChatRequest(Long kbId, String question, Integer topK, Double minScore) {
    }
}
