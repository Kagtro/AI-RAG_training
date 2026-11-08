package com.kagtro.rag.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本清洗的单元测试。
 *
 * 真实 PDF 里常见：每页重复页眉、页码、按行断句、全角字符。
 * 如果清洗把正文误删了，RAG 会"检索不到"或"答非所问"，
 * 但程序不会抛异常，所以需要用测试固定行为。
 *
 * 这些测试是"输入一段假文本 -> 期望清洗后的文本"。
 */
class TextCleanerTest {

    // 被测对象：TextCleaner 不依赖 Spring，可以直接 new
    private final TextCleaner cleaner = new TextCleaner();

    /**
     * 场景：3 页，每页都有重复页眉、页码，以及被换行拆开的两行正文。
     * 期望：
     * 1. 3 页都保留；
     * 2. 页眉被删；
     * 3. 页码行被删；
     * 4. 两行正文合并成一行。
     */
    @Test
    void removesRepeatedHeadersAndPageNumbers() {
        List<PageText> cleaned = cleaner.clean(List.of(
                new PageText(1, "某某公司制度文件\n第1页\n第1页第一行\n第1页第二行"),
                new PageText(2, "某某公司制度文件\n第2页\n第2页第一行\n第2页第二行"),
                new PageText(3, "某某公司制度文件\n第3页\n第3页第一行\n第3页第二行")
        ));

        // 清洗后还是 3 页
        assertEquals(3, cleaned.size());
        for (int i = 0; i < cleaned.size(); i++) {
            PageText page = cleaned.get(i);
            int pageNo = i + 1;

            // 重复页眉不能出现在正文里
            assertFalse(page.text().contains("某某公司制度文件"), "重复页眉应被移除");

            // 页码必须作为"单独一行"被删掉；
            // 注意不能直接 contains("第1页")，因为正文里也有"第1页第一行"
            assertFalse(java.util.Arrays.asList(page.text().split("\n")).contains("第" + pageNo + "页"),
                    "页码行应被移除");

            // 两行正文应该被合并（中文之间没有空格）
            assertTrue(page.text().contains("第" + pageNo + "页第一行第" + pageNo + "页第二行"),
                    "断行应被合并");
        }
    }

    /**
     * 场景：全角字母/数字、中文引号。
     * 期望：转成半角 ABC123 和英文引号。
     * 这能保证后续 BM25/关键词匹配不被全角字符干扰。
     */
    @Test
    void normalizesFullWidthCharactersAndQuotes() {
        List<PageText> cleaned = cleaner.clean(List.of(
                new PageText(1, "ＡＢＣ１２３，“测试”")
        ));

        // 只有 1 页
        assertEquals(1, cleaned.size());
        // 全角 ＡＢＣ１２３ -> 半角 ABC123
        assertTrue(cleaned.get(0).text().contains("ABC123"));
        // 中文引号 “ ” -> 英文引号 " "
        assertTrue(cleaned.get(0).text().contains("\"测试\""));
    }

    /**
     * 场景：两行都是条款开头（"第一条"/"第二条"）。
     * 期望：保留换行，不能合并成一行。
     * 这能防止清洗把法律条款结构压扁。
     */
    @Test
    void keepsStructureLinesSeparate() {
        List<PageText> cleaned = cleaner.clean(List.of(
                new PageText(1, "第一条 这是条款内容。\n第二条 这是第二条内容。")
        ));

        // 1 页
        assertEquals(1, cleaned.size());
        // 两条都在
        assertTrue(cleaned.get(0).text().contains("第一条"));
        assertTrue(cleaned.get(0).text().contains("第二条"));
        // 条款之间还有换行（没有被合并）
        assertTrue(cleaned.get(0).text().contains("\n"), "条款应保留换行，不应合并成一行");
    }
}
