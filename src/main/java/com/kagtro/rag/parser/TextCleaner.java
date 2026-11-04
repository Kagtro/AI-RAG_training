package com.kagtro.rag.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 文本清洗：放在「解析」和「切片」之间。
 *
 * 解决真实 PDF 常见的问题：
 * 1. 每页重复的页眉/页脚；
 * 2. 纯页码行；
 * 3. PDF 按行断开导致的一句话被拆成多行；
 * 4. 控制字符、零宽字符、全角数字/字母；
 * 5. 连续重复行。
 *
 * 设计原则：只做"高置信度"的清洗，宁可少删，不要误删正文。
 */
@Component
public class TextCleaner {

    private static final Logger log = LoggerFactory.getLogger(TextCleaner.class);

    /** 纯页码：3、- 3 -、第 3 页、Page 3 of 10、3 / 10 */
    private static final List<Pattern> PAGE_NUMBER_PATTERNS = List.of(
            Pattern.compile("^\\s*[-—–·•]?\\s*\\d{1,4}\\s*[-—–·•]?\\s*$"),
            Pattern.compile("^\\s*第\\s*\\d+\\s*页(\\s*/\\s*共\\s*\\d+\\s*页)?\\s*$"),
            Pattern.compile("^\\s*page\\s+\\d+(\\s+of\\s+\\d+)?\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*\\d+\\s*/\\s*\\d+\\s*$")
    );

    /** 下一行是条款/列表/标题时，不要和上一行合并 */
    private static final Pattern STRUCTURE_START = Pattern.compile(
            "^\\s*(第[一二三四五六七八九十百零〇\\d]+[条章节]|[一二三四五六七八九十]+[、.]|" +
                    "\\(?[一二三四五六七八九十]+\\)|（[一二三四五六七八九十]+）|" +
                    "\\d+[、.)]|[-*•])\\s*.*");

    /** 句末标点：这些结尾的行不再和下一行合并 */
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？；：.!?;:]$");

    public List<PageText> clean(List<PageText> pages) {
        if (pages == null || pages.isEmpty()) {
            return List.of();
        }

        List<List<String>> pageLines = new ArrayList<>();
        for (PageText page : pages) {
            pageLines.add(toLines(page.text()));
        }

        Set<String> repeated = findRepeatedLines(pageLines);
        List<PageText> result = new ArrayList<>();
        int removed = 0;

        for (int i = 0; i < pages.size(); i++) {
            List<String> lines = new ArrayList<>();
            for (String raw : pageLines.get(i)) {
                String line = normalizeLine(raw);
                if (line.isEmpty()) {
                    lines.add("");
                    continue;
                }
                if (isPageNumber(line)) {
                    removed++;
                    continue;
                }
                if (isRepeatedNoise(line, repeated)) {
                    removed++;
                    continue;
                }
                lines.add(line);
            }

            // 先去重，再合并断行；合并后再去一次重（合并可能产生重复）
            lines = dedupeConsecutive(lines);
            lines = mergeBrokenLines(lines);
            lines = dedupeConsecutive(lines);

            String text = String.join("\n", lines).trim();
            if (!text.isEmpty()) {
                result.add(new PageText(pages.get(i).pageNo(), text));
            }
        }

        log.debug("文本清洗完成: pages={}, removedLines={}", pages.size(), removed);
        return result;
    }

    private List<String> toLines(String text) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] parts = normalized.split("\n", -1);
        List<String> lines = new ArrayList<>(parts.length);
        for (String part : parts) {
            lines.add(part);
        }
        return lines;
    }

    /**
     * 统计每一行出现在多少个不同页面上。
     * 页眉/页脚通常在多数页面重复出现，且比较短。
     */
    private Set<String> findRepeatedLines(List<List<String>> pageLines) {
        int pageCount = pageLines.size();
        if (pageCount < 2) {
            return Set.of();
        }

        Map<String, Integer> pageAppearances = new HashMap<>();
        for (List<String> lines : pageLines) {
            Set<String> seenOnPage = new HashSet<>();
            for (String raw : lines) {
                String line = normalizeLine(raw);
                if (line.isEmpty() || line.length() > 100) {
                    continue;
                }
                String key = line.replaceAll("\\s+", "");
                if (key.length() < 2 || !seenOnPage.add(key)) {
                    continue;
                }
                pageAppearances.merge(key, 1, Integer::sum);
            }
        }

        int threshold = Math.max(2, (int) Math.ceil(pageCount * 0.5));
        Set<String> repeated = new LinkedHashSet<>();
        for (Map.Entry<String, Integer> entry : pageAppearances.entrySet()) {
            if (entry.getValue() >= threshold) {
                repeated.add(entry.getKey());
            }
        }
        return repeated;
    }

    private boolean isRepeatedNoise(String line, Set<String> repeated) {
        if (repeated.isEmpty() || line.length() > 100) {
            return false;
        }
        return repeated.contains(line.replaceAll("\\s+", ""));
    }

    private boolean isPageNumber(String line) {
        for (Pattern pattern : PAGE_NUMBER_PATTERNS) {
            if (pattern.matcher(line).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 清洗单行：
     * - 去零宽字符/控制字符；
     * - 全角数字/字母转半角；
     * - 合并多余空格；
     * - 智能引号统一。
     */
    private String normalizeLine(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF') {
                continue;
            }
            if (c == '\u00A0' || c == '\u3000') {
                sb.append(' ');
                continue;
            }
            if (c == '\t') {
                sb.append(' ');
                continue;
            }
            if (Character.isISOControl(c) && c != '\n') {
                continue;
            }
            // 全角数字/字母 → 半角
            if (c >= '０' && c <= '９') {
                sb.append((char) (c - '０' + '0'));
            } else if (c >= 'Ａ' && c <= 'Ｚ') {
                sb.append((char) (c - 'Ａ' + 'A'));
            } else if (c >= 'ａ' && c <= 'ｚ') {
                sb.append((char) (c - 'ａ' + 'a'));
            } else if (c == '％') {
                sb.append('%');
            } else if (c == '＋') {
                sb.append('+');
            } else if (c == '－') {
                sb.append('-');
            } else {
                sb.append(c);
            }
        }
        String line = sb.toString()
                .replace('“', '"').replace('”', '"')
                .replace('‘', '\'').replace('’', '\'')
                .replaceAll("[ \\t]+", " ")
                .trim();
        return line;
    }

    /**
     * 合并被 PDF 换行拆断的句子：
     * 上一行没有句末标点 + 下一行不是条款/列表/标题 → 合并。
     */
    private List<String> mergeBrokenLines(List<String> lines) {
        List<String> merged = new ArrayList<>();
        for (String line : lines) {
            if (line.isEmpty()) {
                merged.add("");
                continue;
            }
            if (merged.isEmpty()) {
                merged.add(line);
                continue;
            }

            int lastIndex = merged.size() - 1;
            String prev = merged.get(lastIndex);
            if (canMerge(prev, line)) {
                merged.set(lastIndex, join(prev, line));
            } else {
                merged.add(line);
            }
        }
        return merged;
    }

    private boolean canMerge(String prev, String next) {
        if (prev.isEmpty() || next.isEmpty()) {
            return false;
        }
        if (prev.length() > 100 || next.length() > 200) {
            return false;
        }
        if (SENTENCE_END.matcher(prev).find()) {
            return false;
        }
        if (STRUCTURE_START.matcher(next).matches()) {
            return false;
        }
        // 上一行是标题/列表项时，不合并
        if (prev.matches("^\\s*([一二三四五六七八九十]+[、.]|\\d+[、.)]|[-*•]).*")) {
            return false;
        }
        return true;
    }

    private String join(String a, String b) {
        char last = a.charAt(a.length() - 1);
        char first = b.charAt(0);
        // 中文（含中文标点）之间不加空格；英文单词之间保留一个空格
        boolean noSpace = isCjk(last) || isCjk(first);
        return a + (noSpace ? "" : " ") + b;
    }

    private boolean isCjk(char c) {
        return (c >= '\u3000' && c <= '\u303F')   // 中文标点
                || (c >= '\u4E00' && c <= '\u9FFF') // 汉字
                || (c >= '\uFF00' && c <= '\uFFEF'); // 全角符号
    }

    private List<String> dedupeConsecutive(List<String> lines) {
        List<String> result = new ArrayList<>();
        String last = null;
        for (String line : lines) {
            if (line.isEmpty()) {
                if (result.isEmpty() || result.get(result.size() - 1).isEmpty()) {
                    continue;
                }
                result.add("");
                last = null;
                continue;
            }
            if (line.equals(last)) {
                continue;
            }
            result.add(line);
            last = line;
        }
        // 去掉尾部空行
        while (!result.isEmpty() && result.get(result.size() - 1).isEmpty()) {
            result.remove(result.size() - 1);
        }
        return result;
    }
}
