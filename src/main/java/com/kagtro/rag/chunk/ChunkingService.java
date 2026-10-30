package com.kagtro.rag.chunk;

import com.kagtro.rag.parser.PageText;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 先按段落聚合，超过 chunkSize 就切块，并保留 overlap 个字符的上下文。
 * 这是一个够用且可解释的基线版本，后续可以用评测集对比不同参数。
 */
@Service
public class ChunkingService {

    private final int chunkSize;
    private final int overlap;

    public ChunkingService(@Value("${rag.chunk-size:700}") int chunkSize,
                           @Value("${rag.chunk-overlap:120}") int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = Math.min(overlap, chunkSize / 2);
    }

    public List<ChunkDraft> chunk(List<PageText> pages) {
        List<ChunkDraft> result = new ArrayList<>();
        for (PageText page : pages) {
            chunkPage(page, result);
        }
        return result;
    }

    private void chunkPage(PageText page, List<ChunkDraft> result) {
        String normalized = normalize(page.text());
        if (normalized.isBlank()) {
            return;
        }
        List<String> paragraphs = splitParagraphs(normalized);
        StringBuilder buffer = new StringBuilder();
        for (String paragraph : paragraphs) {
            if (paragraph.isBlank()) {
                continue;
            }
            if (buffer.length() > 0 && buffer.length() + paragraph.length() > chunkSize) {
                result.add(toDraft(page.pageNo(), buffer.toString()));
                String tail = tail(buffer.toString(), overlap);
                buffer.setLength(0);
                buffer.append(tail);
            }
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(paragraph);
        }
        if (buffer.length() > 0) {
            result.add(toDraft(page.pageNo(), buffer.toString()));
        }
    }

    private String normalize(String text) {
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \t]+\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private List<String> splitParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        for (String block : text.split("\n\\s*\n")) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() <= chunkSize) {
                paragraphs.add(trimmed);
            } else {
                // 单个段落过长时，按行再拆，避免一块过大
                StringBuilder lineBuffer = new StringBuilder();
                for (String line : trimmed.split("\n")) {
                    if (lineBuffer.length() > 0 && lineBuffer.length() + line.length() > chunkSize) {
                        paragraphs.add(lineBuffer.toString().trim());
                        lineBuffer.setLength(0);
                    }
                    if (lineBuffer.length() > 0) {
                        lineBuffer.append('\n');
                    }
                    lineBuffer.append(line);
                }
                if (lineBuffer.length() > 0) {
                    paragraphs.add(lineBuffer.toString().trim());
                }
            }
        }
        return paragraphs;
    }

    private String tail(String text, int length) {
        if (text.length() <= length) {
            return text;
        }
        return text.substring(text.length() - length);
    }

    private ChunkDraft toDraft(int pageNo, String content) {
        String trimmed = content.trim();
        // 粗略估算：中文 1 字约 1 token，英文 4 字符约 1 token；精确值后续可接入 tokenizer
        return new ChunkDraft(pageNo, trimmed, Math.max(1, trimmed.length()));
    }
}
