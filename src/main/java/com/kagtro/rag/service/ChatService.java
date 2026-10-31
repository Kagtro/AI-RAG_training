package com.kagtro.rag.service;

import com.kagtro.rag.ai.ChatClient;
import com.kagtro.rag.service.RetrievalService.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 问答服务（RAG 的"生成"阶段）：
 * 1. 检索相关片段；
 * 2. 把片段编号拼进 Prompt；
 * 3. 调大模型生成回答；
 * 4. 解析回答里的 [n] 引用，映射回具体片段。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /**
     * 系统提示词：约束模型的角色、资料范围、引用格式和拒答规则。
     * 这段是 RAG 防幻觉的核心，面试可以逐条讲为什么这么写。
     */
    private static final String SYSTEM_PROMPT = """
            你是一个严谨的企业知识库助手。请遵守以下规则：
            1. 只依据【资料】中的内容回答，不要使用资料以外的知识。
            2. 回答中的每个事实后面，必须用 [编号] 标注来源，编号对应资料片段。
            3. 如果【资料】不足以回答问题，直接回答"资料中没有相关信息"，不要猜测。
            4. 用简洁、准确的中文回答，不要输出与问题无关的内容。
            """;

    /** 匹配回答里的引用标记，例如 [1]、[2] */
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");

    private final RetrievalService retrievalService;
    private final ChatClient chatClient;

    public ChatService(RetrievalService retrievalService, ChatClient chatClient) {
        this.retrievalService = retrievalService;
        this.chatClient = chatClient;
    }

    /** 引用信息：前端用它展示"答案来自哪个文件第几页" */
    public record Citation(int index,
                           long chunkId,
                           String fileName,
                           Integer pageNo,
                           double score,
                           String snippet) {
    }

    /** 一次问答的完整结果 */
    public record ChatAnswer(String question,
                             String answer,
                             List<Citation> citations,
                             int retrievedCount) {
    }

    public ChatAnswer ask(long kbId, String question) {
        return ask(kbId, question, null, null);
    }

    public ChatAnswer ask(long kbId, String question, Integer topK, Double minScore) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }

        // 1. 检索：topK + 阈值过滤都在 RetrievalService 里完成
        List<RetrievedChunk> chunks = retrievalService.retrieve(kbId, question, topK, minScore);

        // 2. 一条都没召回：直接拒答，不浪费大模型调用，也避免编造
        if (chunks.isEmpty()) {
            return new ChatAnswer(question, "资料中没有找到相关内容。", List.of(), 0);
        }

        // 3. 拼 Prompt：资料按 [1][2]... 编号，并带上文件名和页码
        String userPrompt = buildUserPrompt(question, chunks);

        // 4. 调大模型
        String answer = chatClient.chat(SYSTEM_PROMPT, userPrompt);

        // 5. 解析回答中的 [n]，映射回片段
        List<Citation> citations = parseCitations(answer, chunks);

        return new ChatAnswer(question, answer, citations, chunks.size());
    }

    /**
     * 流式问答：边生成边通过 SseEmitter 推给前端。
     *
     * 事件约定（前端按事件名处理）：
     * - meta：{"retrievedCount": N}，表示检索完成
     * - delta：{"text": "..."}，回答的一小段
     * - done：{"citations":[...], "retrievedCount": N}
     * - error：{"msg": "..."}
     *
     * 注意：这个方法会阻塞（一直在读模型流），必须放到独立线程池里执行，
     * 所以 Controller 拿到 emitter 后立即返回，由 chatExecutor 执行本方法。
     */
    public void streamTo(long kbId, String question, Integer topK, Double minScore, SseEmitter emitter) {
        try {
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("问题不能为空");
            }

            List<RetrievedChunk> chunks = retrievalService.retrieve(kbId, question, topK, minScore);

            // 没有召回：直接发拒答，不调用大模型
            if (chunks.isEmpty()) {
                emitter.send(SseEmitter.event().name("delta")
                        .data(Map.of("text", "资料中没有找到相关内容。")));
                emitter.send(SseEmitter.event().name("done")
                        .data(Map.of("citations", List.of(), "retrievedCount", 0)));
                emitter.complete();
                return;
            }

            emitter.send(SseEmitter.event().name("meta")
                    .data(Map.of("retrievedCount", chunks.size())));

            String userPrompt = buildUserPrompt(question, chunks);
            StringBuilder answer = new StringBuilder();

            // 模型每吐一小段，就 append 并推给前端
            chatClient.chatStream(SYSTEM_PROMPT, userPrompt, delta -> {
                answer.append(delta);
                try {
                    emitter.send(SseEmitter.event().name("delta").data(Map.of("text", delta)));
                } catch (IOException e) {
                    // 客户端断开/网络异常：转成运行时异常，外层统一收尾
                    throw new UncheckedIOException(e);
                }
            });

            // 流结束后，再解析引用并发送 done
            List<Citation> citations = parseCitations(answer.toString(), chunks);
            emitter.send(SseEmitter.event().name("done")
                    .data(Map.of("citations", citations, "retrievedCount", chunks.size())));
            emitter.complete();
        } catch (Exception e) {
            log.error("流式问答失败", e);
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("msg", e.getMessage() == null ? "服务异常" : e.getMessage())));
            } catch (IOException ignored) {
                // 连接已经断了，忽略
            }
            emitter.completeWithError(e);
        }
    }

    private String buildUserPrompt(String question, List<RetrievedChunk> chunks) {        StringBuilder sb = new StringBuilder();
        sb.append("【资料】\n");
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            sb.append('[').append(i + 1).append(']');
            sb.append("（文件：").append(chunk.fileName());
            sb.append("，第 ").append(chunk.pageNo() == null ? "未知" : chunk.pageNo()).append(" 页）\n");
            sb.append(chunk.content()).append("\n\n");
        }
        sb.append("【问题】\n").append(question).append('\n');
        return sb.toString();
    }

    /**
     * 从模型回答中提取 [n]，去重后映射成 Citation。
     * 只保留 1 <= n <= 片段数 的编号，防止模型编出不存在的引用。
     */
    private List<Citation> parseCitations(String answer, List<RetrievedChunk> chunks) {
        Set<Integer> indexes = new LinkedHashSet<>();
        Matcher matcher = CITATION_PATTERN.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index >= 1 && index <= chunks.size()) {
                indexes.add(index);
            }
        }
        List<Citation> citations = new ArrayList<>();
        for (Integer index : indexes) {
            RetrievedChunk chunk = chunks.get(index - 1);
            String snippet = chunk.content().length() <= 120
                    ? chunk.content()
                    : chunk.content().substring(0, 120) + "...";
            citations.add(new Citation(index, chunk.chunkId(), chunk.fileName(),
                    chunk.pageNo(), chunk.score(), snippet));
        }
        return citations;
    }
}
