package com.kagtro.rag.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
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
            case "docx", "doc" -> List.of(new PageText(1, extractDocx(path)));
            case "md", "txt" -> List.of(new PageText(1, Files.readString(path, StandardCharsets.UTF_8)));
            default -> throw new IllegalArgumentException("不支持的文件类型: " + type);
        };
    }

    private List<PageText> extractPdf(Path path) throws IOException {
        List<PageText> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            int total = document.getNumberOfPages();
            for (int page = 1; page <= total; page++) {
                PDFTextStripper stripper = new PDFTextStripper();
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

    private String extractDocx(Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = Files.newInputStream(path);
             XWPFDocument document = new XWPFDocument(in)) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                if (!paragraph.getText().isBlank()) {
                    sb.append(paragraph.getText()).append('\n');
                }
            }
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        sb.append(cell.getText()).append('\t');
                    }
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }
}
