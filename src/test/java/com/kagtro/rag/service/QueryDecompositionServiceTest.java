package com.kagtro.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kagtro.rag.ai.ChatClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 查询拆解服务的单元测试。
 *
 * 这个类会调用大模型，但单元测试不能真的花钱调 API，
 * 所以用 Mockito 伪造一个 ChatClient：
 * - mock(...) 创建一个假对象；
 * - when(...).thenReturn(...) 指定"假模型"返回什么；
 * - verify(...) 检查"假模型"到底有没有被调用、调用几次。
 *
 * 这样测试既快、又不花钱，还能验证逻辑。
 */
class QueryDecompositionServiceTest {

    /**
     * 场景：一个包含三个子问题的长问题。
     * 期望：
     * 1. 识别为多意图；
     * 2. 调用一次大模型；
     * 3. 返回 3 个子查询。
     */
    @Test
    void decomposesMultiIntentQuestionIntoSubQueries() {
        // 假的大模型客户端
        ChatClient chatClient = mock(ChatClient.class);
        // 无论传什么 prompt，都返回这三个子查询（模拟模型输出 JSON 数组）
        when(chatClient.chat(anyString(), anyString()))
                .thenReturn("[\"工资结清时限\",\"经济补偿计算标准\",\"公积金账户转移封存\"]");

        // 用假模型构造服务：enabled=true 表示拆解开关打开
        QueryDecompositionService service =
                new QueryDecompositionService(chatClient, new ObjectMapper(), true);

        // 这个问题的顿号 + 多个疑问词（何时/怎么/怎么办）会触发多意图判断
        List<String> result = service.decompose(
                "劳动合同终止时，工资何时结清、经济补偿怎么算、公积金账户怎么办？");

        // 期望拆成 3 个子问题
        assertEquals(3, result.size());
        // 其中包含"工资结清时限"
        assertTrue(result.contains("工资结清时限"));
        // 大模型只应该被调用一次（不能一个问题调多次）
        verify(chatClient, times(1)).chat(anyString(), anyString());
    }

    /**
     * 场景：单意图问题"年休假有多少天？"。
     * 期望：
     * 1. 不拆解，返回空列表；
     * 2. 完全不调用大模型（省钱、省延迟）。
     */
    @Test
    void doesNotCallModelForSingleIntentQuestion() {
        ChatClient chatClient = mock(ChatClient.class);
        QueryDecompositionService service =
                new QueryDecompositionService(chatClient, new ObjectMapper(), true);

        List<String> result = service.decompose("年休假有多少天？");

        // 单意图不拆
        assertTrue(result.isEmpty());
        // 假模型一次都不能被调用
        verifyNoInteractions(chatClient);
    }

    /**
     * 场景：配置里把查询拆解关掉。
     * 期望：即使问题看起来多意图，也直接返回空，不调模型。
     * 这用于做 A/B 实验（开/关拆解对比效果）。
     */
    @Test
    void returnsEmptyWhenDisabled() {
        ChatClient chatClient = mock(ChatClient.class);
        QueryDecompositionService service =
                new QueryDecompositionService(chatClient, new ObjectMapper(), false);

        List<String> result = service.decompose("工资和年休假分别怎么算？");

        // 开关关闭 -> 不拆
        assertTrue(result.isEmpty());
        // 也不应该调模型
        verifyNoInteractions(chatClient);
    }
}
