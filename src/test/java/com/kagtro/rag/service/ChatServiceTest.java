package com.kagtro.rag.service;

import com.kagtro.rag.ai.ChatClient;
import com.kagtro.rag.dao.QaLogDao;
import com.kagtro.rag.service.RetrievalService.RetrievalResult;
import com.kagtro.rag.service.RetrievalService.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatService 引用对齐的单测。
 *
 * 不启动 Spring、不连数据库、不调真实模型：
 * - RetrievalService 返回固定片段；
 * - ChatClient 返回固定回答；
 * - QaLogDao 用 mock 吞掉日志。
 *
 * 重点验证：
 * 1. 模型写了越界编号（[10]）时，回答里的编号会被清理，引用会补成正确片段；
 * 2. 模型引用了错误片段（[2]）时，错误引用被丢掉，换成和回答内容匹配的片段。
 */
class ChatServiceTest {

    private ChatService serviceWith(List<RetrievedChunk> chunks, String modelAnswer) {
        RetrievalService retrievalService = mock(RetrievalService.class);
        ChatClient chatClient = mock(ChatClient.class);
        QaLogDao qaLogDao = mock(QaLogDao.class);

        when(retrievalService.retrieveWithDecomposition(anyLong(), anyString(), any(), any()))
                .thenReturn(new RetrievalResult(chunks, List.of("测试问题")));
        when(chatClient.chat(anyString(), anyString())).thenReturn(modelAnswer);

        return new ChatService(retrievalService, chatClient, qaLogDao);
    }

    private RetrievedChunk annualLeaveChunk() {
        return new RetrievedChunk(
                101L, 1L, 1,
                "职工累计工作满10年不满20年的，年休假10天。",
                "03_annual_leave_regulation.txt",
                0.92, "vector");
    }

    private RetrievedChunk laborContractChunk() {
        return new RetrievedChunk(
                102L, 2L, 1,
                "用人单位应当自用工之日起一个月内订立书面劳动合同。",
                "01_labor_contract_law.txt",
                0.81, "bm25");
    }

    @Test
    void removesOutOfRangeCitationAndAlignsCorrectChunk() {
        // 模型把法条编号写成了 [10]，片段只有 2 条，应该被清理
        ChatService service = serviceWith(
                List.of(annualLeaveChunk(), laborContractChunk()),
                "职工累计工作满10年不满20年的，年休假10天[10]。");

        ChatService.ChatAnswer answer = service.ask(1L, "年休假多少天", 8, 0.5);

        // 回答里不再有越界编号
        assertFalse(answer.answer().contains("[10]"));
        // 引用被后处理补成了年休假条例
        assertEquals(1, answer.citations().size());
        assertEquals("03_annual_leave_regulation.txt", answer.citations().get(0).fileName());
        assertTrue(answer.answer().contains("年休假10天"));
    }

    @Test
    void dropsWrongCitationAndKeepsBestAlignedChunk() {
        // 模型引用了 [2]（劳动合同），但回答内容实际来自年休假片段
        ChatService service = serviceWith(
                List.of(annualLeaveChunk(), laborContractChunk()),
                "职工累计工作满10年不满20年的，年休假10天[2]。");

        ChatService.ChatAnswer answer = service.ask(1L, "年休假多少天", 8, 0.5);

        // 错误编号 [2] 被清理
        assertFalse(answer.answer().contains("[2]"));
        // 只保留和回答内容匹配的年休假片段
        assertEquals(1, answer.citations().size());
        assertEquals("03_annual_leave_regulation.txt", answer.citations().get(0).fileName());
        assertEquals(101L, answer.citations().get(0).chunkId());
    }
}
