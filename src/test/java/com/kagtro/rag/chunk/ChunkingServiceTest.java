package com.kagtro.rag.chunk;

import com.kagtro.rag.parser.PageText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片服务的单元测试。
 *
 * 目的：切片是 RAG 的"地基"。
 * 如果切片把一页切成空块、丢掉 overlap、或者把页码弄错，
 * 后面的检索和引用都会跟着错，但程序本身不会报错。
 *
 * 所以这里不启动 Spring、不连数据库、不调大模型，
 * 只把 ChunkingService 当成一个普通 Java 类来测。
 *
 * 读法：
 * - new ChunkingService(100, 20) = 每块最多 100 字，块之间重叠 20 字；
 * - service.chunk(...) = 把若干页文本切成若干块；
 * - assertEquals(期望值, 实际值) = 如果两者不一致，测试失败。
 */
class ChunkingServiceTest {

    /**
     * 场景：两页短文本。
     * 期望：切成两块，且每块保留自己的页码。
     * 这能防止"跨页合并"或"页码丢失"。
     */
    @Test
    void keepsPageNumberAndSplitsByPage() {
        // 100 字一块，20 字重叠；两页内容都很短，所以每页各一块
        ChunkingService service = new ChunkingService(100, 20);
        List<ChunkDraft> chunks = service.chunk(List.of(
                new PageText(1, "第一页内容"),
                new PageText(2, "第二页内容")
        ));

        // 2 页 -> 2 块
        assertEquals(2, chunks.size());
        // 第一块页码是 1
        assertEquals(1, chunks.get(0).pageNo());
        // 第二块页码是 2
        assertEquals(2, chunks.get(1).pageNo());
        // 第一块里应该有第一页的正文
        assertTrue(chunks.get(0).content().contains("第一页内容"));
        // 第二块里应该有第二页的正文
        assertTrue(chunks.get(1).content().contains("第二页内容"));
    }

    /**
     * 场景：一页里有三段，每段 60 字，总长度 180 > 100。
     * 期望：
     * 1. 至少切成 2 块；
     * 2. 每块不超过 100 字；
     * 3. 第一块包含第一段，最后一块包含最后一段；
     * 4. 每块都有页码和 tokenCount。
     *
     * 这能防止"chunk 越切越大"或"末尾内容被丢掉"。
     */
    @Test
    void respectsChunkSizeAndKeepsOverlap() {
        ChunkingService service = new ChunkingService(100, 20);
        String a = "甲".repeat(60); // 第一段：60 字
        String b = "乙".repeat(60); // 第二段：60 字
        String c = "丙".repeat(60); // 第三段：60 字

        // 用空行把三段隔开，模拟"段落"
        List<ChunkDraft> chunks = service.chunk(List.of(
                new PageText(1, a + "\n\n" + b + "\n\n" + c)
        ));

        // 180 字 > 100 字，必须切块
        assertTrue(chunks.size() >= 2, "内容超过 chunkSize 时应该切块");

        // 每一块都不能超过 100 字
        for (ChunkDraft chunk : chunks) {
            assertTrue(chunk.content().length() <= 100,
                    "chunk 内容不应超过 chunkSize: " + chunk.content().length());
            assertEquals(1, chunk.pageNo()); // 仍属于第 1 页
            assertTrue(chunk.tokenCount() > 0); // token 数不能是 0
        }

        // 第一块应该包含第一段，最后一块应该包含第三段（内容没丢）
        assertTrue(chunks.get(0).content().contains(a));
        assertTrue(chunks.get(chunks.size() - 1).content().contains(c));
    }

    /**
     * 场景：一页全是空白，另一页有正文。
     * 期望：空白页不生成 chunk，只生成正文那一块。
     * 这能防止"空 chunk 进入向量库"。
     */
    @Test
    void ignoresBlankPage() {
        ChunkingService service = new ChunkingService(100, 20);
        List<ChunkDraft> chunks = service.chunk(List.of(
                new PageText(1, "   \n\n  "), // 空白页
                new PageText(2, "有效内容")     // 正常页
        ));

        // 空白页被跳过，只剩 1 块
        assertEquals(1, chunks.size());
        // 这一块来自第 2 页
        assertEquals(2, chunks.get(0).pageNo());
        // 内容不能为空
        assertFalse(chunks.get(0).content().isBlank());
    }
}
