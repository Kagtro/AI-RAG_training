package com.kagtro.rag.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class TextExtractor {

    public List<PageText> extract(Path path, String fileType) throws IOException {
        String type = fileType == null ? "" : fileType.toLowerCase();
        return switch (type) {
            case "pdf" -> extractPdf(path);
            case "docx" -> List.of(new PageText(1, extractDocx(path)));
            case "doc" -> throw new IllegalArgumentException("暂不支持旧版 .doc，请另存为 .docx 后上传");
            case "md", "txt" -> List.of(new PageText(1, readText(path)));
            default -> throw new IllegalArgumentException("不支持的文件类型: " + type);
        };
    }

    /**
     * PDF：按页解析，保留页码。
     * setSortByPosition(true) 能让多栏/表格的阅读顺序更接近人眼顺序。
     */
    private List<PageText> extractPdf(Path path) throws IOException {
        List<PageText> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            int total = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setSuppressDuplicateOverlappingText(true);
            stripper.setLineSeparator("\n");

            for (int page = 1; page <= total; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                if (text != null && !text.isBlank()) {
                    pages.add(new PageText(page, text));
                }
            }
        }
        return pages;
    }

    /**
     * DOCX：按文档顺序读取段落和表格（原来先读所有段落再读表格，顺序会乱）。
     */
    private String extractDocx(Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = Files.newInputStream(path);
             XWPFDocument document = new XWPFDocument(in)) {
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText();
                    if (text != null && !text.isBlank()) {
                        sb.append(text.trim()).append('\n');
                    }
                } else if (element instanceof XWPFTable table) {
                    appendTable(sb, table);
                }
            }
        }
        return sb.toString();
    }

    private void appendTable(StringBuilder sb, XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                String text = cell.getText() == null ? "" : cell.getText().trim();
                if (!text.isEmpty()) {
                    cells.add(text.replaceAll("\\s+", " "));
                }
            }
            if (!cells.isEmpty()) {
                sb.append(String.join(" | ", cells)).append('\n');
            }
        }
        sb.append('\n');
    }

    /**
     * 文本文件：优先 UTF-8，失败时退回 GB18030（兼容常见中文乱码文件）。
     */
    private String readText(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        String text;
        try {
            text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            text = new String(bytes, Charset.forName("GB18030"));
        }
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        return text;
    }
}
