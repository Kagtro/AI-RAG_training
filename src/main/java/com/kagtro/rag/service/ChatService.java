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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * 4. 解析 / 对齐回答里的引用，映射回具体片段。
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
            2. 每个事实后用 [编号] 标注来源；编号只能是资料片段序号（从 1 开始，最大不超过资料条数），不要写法律条款号，也不要用不存在的编号。
            3. 如果【资料】完全没有相关规则，直接回答"资料中没有相关信息"，不要猜测。
            4. 用简洁、准确的中文回答，不要输出与问题无关的内容。
            5. 如果问题包含多个子问题，请分别回答，不要把不同子问题合并成同一个结论。
            6. 如果资料给出了认定/排除条件，而题目描述的事实明显不符合这些条件，应回答"不属于/不得认定/不视同"并说明不符合哪项条件，不要回答"资料中没有相关信息"。
            7. 如果资料有相关规则，但没有直接规定问题所问的事项，回答"资料中没有明确规定"，不要用法理、常识或外部知识推断。
            示例 1（否定结论）：
            资料：第十四条 职工有下列情形之一的，应当认定为工伤：在工作时间和工作场所内，因工作原因受到事故伤害的……
            问题：职工周末在家休息时受伤，算不算工伤？
            回答：不属于工伤。因为不符合工作时间、工作场所、工作原因等条件。
            示例 2（标签场景）：
            资料：第十四条 职工有下列情形之一的，应当认定为工伤：在工作时间和工作场所内，因工作原因受到事故伤害的……
            问题：职工在单位宿舍休息时受伤，算不算工伤？
            回答：不属于工伤。因为宿舍休息不属于在工作时间、工作场所内因工作原因受到事故伤害。
            示例 3（资料未直接规定）：
            资料：停工留薪期内原工资福利待遇不变，由所在单位按月支付；职工累计工作满 1 年不满 10 年的，年休假 5 天……
            问题：工伤职工在停工留薪期内能否享受年休假？
            回答：资料中没有明确规定。
            """;

    /** 匹配回答里的引用标记，例如 [1]、[2] */
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");

    private final RetrievalService retrievalService;
    private final ChatClient chatClient;
    private final com.kagtro.rag.dao.QaLogDao qaLogDao;

    public ChatService(RetrievalService retrievalService,
                       ChatClient chatClient,
                       com.kagtro.rag.dao.QaLogDao qaLogDao) {
        this.retrievalService = retrievalService;
        this.chatClient = chatClient;
        this.qaLogDao = qaLogDao;
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
        long started = System.currentTimeMillis();

        // 1. 检索：按需做查询拆解，然后每个子问题独立召回并融合
        var retrieval = retrievalService.retrieveWithDecomposition(kbId, question, topK, minScore);
        List<RetrievedChunk> chunks = retrieval.chunks();

        // 2. 一条都没召回：直接拒答，不浪费大模型调用，也避免编造
        if (chunks.isEmpty()) {
            String refusal = "资料中没有找到相关内容。";
            qaLogDao.log(kbId, question, List.of(), refusal, System.currentTimeMillis() - started);
            return new ChatAnswer(question, refusal, List.of(), 0);
        }

        // 3. 拼 Prompt：资料按 [1][2]... 编号，并带上文件名和页码
        String userPrompt = buildUserPrompt(question, chunks);

        // 4. 调大模型
        String answer = chatClient.chat(SYSTEM_PROMPT, userPrompt);

        // 5. 引用对齐：先保留合法引用，再用回答文本补上缺失/错误的引用
        List<Citation> citations = alignCitations(answer, chunks,
                parseCitations(answer, chunks), retrieval.queries());

        // 6. 清掉不存在/未通过校验的引用编号，避免前端看到错误的编号
        answer = sanitizeAnswer(answer, chunks, citations);

        qaLogDao.log(kbId, question, chunkIds(chunks), answer, System.currentTimeMillis() - started);
        return new ChatAnswer(question, answer, citations, chunks.size());
    }

    /**
     * 流式问答：边生成边通过 SseEmitter 推给前端。
     *
     * 事件约定（前端按事件名处理）：
     * - meta：{"retrievedCount":N,"documentCount":M,"chunks":[...]} 检索完成（含片段明细）
     * - delta：{"text":"..."} 回答的一小段
     * - done： {"citations":[...],"retrievedCount":N,"answer":"清洗后的完整回答"}
     * - error：{"msg":"..."}
     *
     * 注意：这个方法会阻塞（一直在读模型流），必须放到独立线程池里执行，
     * 所以 Controller 拿到 emitter 后立即返回，由 chatExecutor 执行本方法。
     */
    public void streamTo(long kbId, String question, Integer topK, Double minScore, SseEmitter emitter) {
        try {
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("问题不能为空");
            }
            long started = System.currentTimeMillis();

            var retrieval = retrievalService.retrieveWithDecomposition(kbId, question, topK, minScore);
            List<RetrievedChunk> chunks = retrieval.chunks();
            List<String> queries = retrieval.queries();

            // 没有召回：也发 meta，方便前端显示"未检索到片段"
            if (chunks.isEmpty()) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("retrievedCount", 0);
                meta.put("documentCount", 0);
                meta.put("queries", queries);
                meta.put("chunks", List.of());
                emitter.send(SseEmitter.event().name("meta").data(meta));

                String refusal = "资料中没有找到相关内容。";
                qaLogDao.log(kbId, question, List.of(), refusal, System.currentTimeMillis() - started);
                emitter.send(SseEmitter.event().name("delta").data(Map.of("text", refusal)));
                emitter.send(SseEmitter.event().name("done")
                        .data(Map.of("citations", List.of(), "retrievedCount", 0, "answer", refusal)));
                emitter.complete();
                return;
            }

            emitter.send(SseEmitter.event().name("meta").data(buildMeta(chunks, queries)));

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

            // 流结束后：对齐引用 + 清洗越界引用
            String rawAnswer = answer.toString();
            List<Citation> citations = alignCitations(rawAnswer, chunks,
                    parseCitations(rawAnswer, chunks), retrieval.queries());
            String cleanAnswer = sanitizeAnswer(rawAnswer, chunks, citations);

            Map<String, Object> done = new LinkedHashMap<>();
            done.put("citations", citations);
            done.put("retrievedCount", chunks.size());
            done.put("answer", cleanAnswer);
            emitter.send(SseEmitter.event().name("done").data(done));
            qaLogDao.log(kbId, question, chunkIds(chunks), cleanAnswer, System.currentTimeMillis() - started);
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

    private String buildUserPrompt(String question, List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("【资料】（共 ").append(chunks.size()).append(" 条，引用编号只能是 1~")
                .append(chunks.size()).append("）\n");
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

    /** 提取召回片段的 id，用于 qa_log */
    private List<Long> chunkIds(List<RetrievedChunk> chunks) {
        List<Long> ids = new ArrayList<>(chunks.size());
        for (RetrievedChunk chunk : chunks) {
            ids.add(chunk.chunkId());
        }
        return ids;
    }

    /** meta 事件：片段数、来源文档数、实际检索用的查询、每个片段的摘要（前端展示检索详情用） */
    private Map<String, Object> buildMeta(List<RetrievedChunk> chunks, List<String> queries) {
        Set<String> files = new LinkedHashSet<>();
        List<Map<String, Object>> infos = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            files.add(chunk.fileName());

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("index", i + 1);
            info.put("chunkId", chunk.chunkId());
            info.put("fileName", chunk.fileName());
            info.put("pageNo", chunk.pageNo());
            info.put("score", Math.round(chunk.score() * 1000) / 1000.0);
            info.put("source", chunk.source());
            info.put("snippet", snippet(chunk.content(), 200));
            infos.add(info);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("retrievedCount", chunks.size());
        meta.put("documentCount", files.size());
        meta.put("queries", queries);
        meta.put("chunks", infos);
        return meta;
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
            citations.add(toCitation(index, chunks.get(index - 1)));
        }
        return citations;
    }

    /**
     * 引用对齐（后处理，不调模型）：
     * 1. 模型给出的合法引用先保留；
     * 2. 如果引用缺失/被过滤，用"回答文本"和每个片段的字符二元组重合度找最相关的 1~2 个片段补上。
     *
     * 这解决的是：模型写 [10]（法条编号）被过滤后引用为空，
     * 或者引用了错误的片段编号。
     */
    private List<Citation> alignCitations(String answer,
                                          List<RetrievedChunk> chunks,
                                          List<Citation> parsed,
                                          List<String> queries) {
        if (answer == null || chunks == null || chunks.isEmpty()) {
            return parsed;
        }
        // 拒答类回答不要强行挂引用
        if (answer.contains("资料中没有相关信息") || answer.contains("未找到")) {
            return parsed;
        }

        List<Citation> result = new ArrayList<>();
        Set<Long> citedIds = new HashSet<>();
        Set<String> citedFiles = new HashSet<>();

        Set<String> answerGrams = bigrams(answer);
        if (answerGrams.isEmpty()) {
            return result;
        }

        record ScoredChunk(int index, RetrievedChunk chunk, double score) {
        }

        List<ScoredChunk> scored = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            Set<String> chunkGrams = bigrams(chunk.content());
            int overlap = 0;
            for (String gram : answerGrams) {
                if (chunkGrams.contains(gram)) {
                    overlap++;
                }
            }
            double score = (double) overlap / answerGrams.size();
            scored.add(new ScoredChunk(i + 1, chunk, score));
        }

        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());

        Map<Long, Double> scoreByChunkId = new java.util.HashMap<>();
        for (ScoredChunk item : scored) {
            scoreByChunkId.put(item.chunk().chunkId(), item.score());
        }

        double bestScore = scored.isEmpty() ? 0 : scored.get(0).score();

        // 先校验模型自己给的引用：和回答内容几乎不重合的，说明引错了，丢掉
        double parsedThreshold = Math.max(0.12, bestScore * 0.25);
        for (Citation citation : parsed) {
            double relevance = scoreByChunkId.getOrDefault(citation.chunkId(), 0.0);
            if (relevance >= parsedThreshold) {
                result.add(citation);
                citedIds.add(citation.chunkId());
                citedFiles.add(citation.fileName());
            }
        }

        // 多子问题时，确保每个子问题至少有一个来源被引用（选择该子查询最匹配且答案有涉及的片段）
        if (queries != null && queries.size() > 1) {
            for (String query : queries) {
                if (result.size() >= 6) {
                    break;
                }
                Set<String> queryGrams = bigrams(query);
                int bestIndex = -1;
                RetrievedChunk bestChunk = null;
                double bestQueryScore = 0;
                for (int i = 0; i < chunks.size(); i++) {
                    RetrievedChunk chunk = chunks.get(i);
                    if (citedIds.contains(chunk.chunkId()) || citedFiles.contains(chunk.fileName())) {
                        continue;
                    }
                    Double answerScore = scoreByChunkId.get(chunk.chunkId());
                    if (answerScore == null || answerScore < 0.08) {
                        continue;
                    }
                    Set<String> chunkGrams = bigrams(chunk.content());
                    int overlap = 0;
                    for (String gram : queryGrams) {
                        if (chunkGrams.contains(gram)) {
                            overlap++;
                        }
                    }
                    double queryScore = queryGrams.isEmpty() ? 0 : (double) overlap / queryGrams.size();
                    if (queryScore > bestQueryScore) {
                        bestQueryScore = queryScore;
                        bestChunk = chunk;
                        bestIndex = i + 1;
                    }
                }
                if (bestChunk != null && bestQueryScore >= 0.10) {
                    result.add(toCitation(bestIndex, bestChunk));
                    citedIds.add(bestChunk.chunkId());
                    citedFiles.add(bestChunk.fileName());
                }
            }
        }

        double threshold = Math.max(0.12, bestScore * 0.35);
        int maxAdd = 3;
        int added = 0;

        for (ScoredChunk item : scored) {
            if (added >= maxAdd) {
                break;
            }
            if (item.score() < threshold) {
                break;
            }
            if (citedIds.contains(item.chunk().chunkId())) {
                continue;
            }
            // 同一文件只补一个片段，避免同一文档塞一堆引用；跨文档题会补到不同文件
            if (citedFiles.contains(item.chunk().fileName())) {
                continue;
            }
            result.add(toCitation(item.index(), item.chunk()));
            citedIds.add(item.chunk().chunkId());
            citedFiles.add(item.chunk().fileName());
            added++;
        }
        return result;
    }

    /** 把文本切成中文二元组，用于粗略计算"回答和片段有多像" */
    private Set<String> bigrams(String text) {
        String normalized = text
                .replaceAll("\\s+", "")
                .replaceAll("[，。；：！？、（）【】《》\"'“”‘’*.,;:!?()\\[\\]{}<>/\\\\|-]", "");
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 1 < normalized.length(); i++) {
            grams.add(normalized.substring(i, i + 2));
        }
        return grams;
    }

    /** 把片段转成 Citation，index 从 1 开始 */
    private Citation toCitation(int index, RetrievedChunk chunk) {
        return new Citation(index, chunk.chunkId(), chunk.fileName(),
                chunk.pageNo(), chunk.score(), snippet(chunk.content(), 120));
    }

    /** 删除不存在/未通过对齐校验的引用编号，例如资料只有 5 条时清掉 [10] */
    private String sanitizeAnswer(String answer, List<RetrievedChunk> chunks, List<Citation> citations) {
        if (answer == null || answer.isEmpty()) {
            return "";
        }
        Set<Integer> allowed = new LinkedHashSet<>();
        for (Citation citation : citations) {
            allowed.add(citation.index());
        }

        Matcher matcher = CITATION_PATTERN.matcher(answer);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index >= 1 && index <= chunks.size() && allowed.contains(index)) {
                matcher.appendReplacement(sb, "[" + index + "]");
            } else {
                matcher.appendReplacement(sb, "");
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String snippet(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        String oneLine = content.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= maxLength ? oneLine : oneLine.substring(0, maxLength) + "...";
    }
}
