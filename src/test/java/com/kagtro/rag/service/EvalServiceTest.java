package com.kagtro.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kagtro.rag.dao.EvalDao;
import com.kagtro.rag.service.ChatService.ChatAnswer;
import com.kagtro.rag.service.ChatService.Citation;
import com.kagtro.rag.service.RetrievalService.RetrievalResult;
import com.kagtro.rag.service.RetrievalService.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EvalService 指标计算的单测。
 *
 * 不连数据库、不调模型：
 * - EvalDao 返回 4 道固定题；
 * - RetrievalService 返回固定召回结果；
 * - ChatService 返回固定回答和引用。
 *
 * 重点验证 Recall、跨文档召回、引用覆盖、答案正确率、拒答率的计算。
 */
class EvalServiceTest {

    private Map<String, Object> row(int id, String category, String question,
                                    String expectedFiles, String keywords) {
        return Map.of(
                "id", id,
                "category", category,
                "difficulty", category.equals("unanswerable") ? "hard" : "easy",
                "question", question,
                "doc_ids", expectedFiles,
                "optional_files", "",
                "ground_truth", "标准答案",
                "keywords", keywords
        );
    }

    private RetrievedChunk chunk(String fileName) {
        return new RetrievedChunk(1L, 1L, 1, "片段内容", fileName, 0.9, "vector");
    }

    private ChatAnswer answer(String text, String citationFile) {
        List<Citation> citations = citationFile == null
                ? List.of()
                : List.of(new Citation(1, 1L, citationFile, 1, 0.9, "片段"));
        return new ChatAnswer("问题", text, citations, citationFile == null ? 0 : 1);
    }

    @Test
    void computesRecallGroundednessAccuracyAndRefusalRates() {
        EvalDao evalDao = mock(EvalDao.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        ChatService chatService = mock(ChatService.class);

        List<Map<String, Object>> rows = List.of(
                row(1, "answerable", "题1", "a.txt", "10天"),
                row(2, "answerable", "题2", "b.txt", "5天"),
                row(3, "cross_doc", "题3", "a.txt;b.txt", "10天"),
                row(4, "unanswerable", "题4", "", "没有")
        );
        when(evalDao.findAll()).thenReturn(rows);

        when(retrievalService.retrieveWithDecomposition(anyLong(), anyString(), anyInt(), anyDouble()))
                .thenAnswer(inv -> {
                    String question = inv.getArgument(1);
                    // 题 1/2/3 都只召回 a.txt；题 4 也召回一个无关片段
                    return new RetrievalResult(List.of(chunk("a.txt")), List.of(question));
                });

        when(chatService.ask(anyLong(), anyString(), any(), any()))
                .thenAnswer(inv -> {
                    String question = inv.getArgument(1);
                    return switch (question) {
                        case "题1" -> answer("10天[1]", "a.txt");
                        case "题2" -> answer("资料中没有相关信息", null);
                        case "题3" -> answer("10天[1]", "a.txt");
                        default -> answer("资料中没有相关信息", null);
                    };
                });

        EvalService service = new EvalService(evalDao, retrievalService, chatService, new ObjectMapper());
        EvalService.EvalReport report = service.run(1L, 8, 0.5);

        assertEquals(4, report.total());
        assertEquals(2, report.answerable());
        assertEquals(1, report.crossDoc());
        assertEquals(1, report.unanswerable());

        // 可答题 2 道，只命中 1 道
        assertEquals(0.5, report.recallAtK(), 0.0001);
        // 跨文档 1 道，只召回了 a.txt，没覆盖 a+b
        assertEquals(0.0, report.crossRecallAtK(), 0.0001);
        // 引用覆盖：题 1 覆盖，题 2、题 3 没覆盖 -> 1/3
        assertEquals(1.0 / 3.0, report.groundedness(), 0.0001);
        // 答案正确：题 1、题 3 正确 -> 2/3
        assertEquals(2.0 / 3.0, report.answerAccuracy(), 0.0001);
        // 不可答题正确拒答 -> 1/1
        assertEquals(1.0, report.refusalAccuracy(), 0.0001);
    }
}
