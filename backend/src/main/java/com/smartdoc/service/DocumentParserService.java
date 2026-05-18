package com.smartdoc.service;

import com.alibaba.excel.EasyExcel;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class DocumentParserService {

    public String parseDocument(byte[] fileBytes, String fileType) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new IllegalArgumentException("文件内容为空");
        }

        String detectedType = detectFileType(fileBytes, fileType);
        log.debug("检测到文件类型: {}", detectedType);

        switch (detectedType) {
            case "docx":
                return parseDocx(fileBytes);
            case "doc":
                return parseDoc(fileBytes);
            case "pdf":
                return parsePdf(fileBytes);
            case "txt":
                return parseTxt(fileBytes);
            default:
                throw new IllegalArgumentException("不支持的文件类型: " + detectedType);
        }
    }

    public String detectFileType(byte[] fileBytes, String providedType) {
        if (providedType != null && !"auto".equals(providedType)) {
            return providedType;
        }

        if (fileBytes.length >= 8) {
            if (fileBytes[0] == (byte) 0xD0 && fileBytes[1] == (byte) 0xCF && 
                fileBytes[2] == (byte) 0x11 && fileBytes[3] == (byte) 0xE0 &&
                fileBytes[4] == (byte) 0xA1 && fileBytes[5] == (byte) 0xB1 &&
                fileBytes[6] == (byte) 0x1A && fileBytes[7] == (byte) 0xE1) {
                return "doc";
            }
        }

        if (fileBytes.length >= 4) {
            if (fileBytes[0] == 0x50 && fileBytes[1] == 0x4B && fileBytes[2] == 0x03 && fileBytes[3] == 0x04) {
                return "docx";
            }
            if (fileBytes[0] == 0x25 && fileBytes[1] == 0x50 && fileBytes[2] == 0x44 && fileBytes[3] == 0x46) {
                return "pdf";
            }
        }

        try {
            new String(fileBytes, StandardCharsets.UTF_8);
            return "txt";
        } catch (Exception e) {
            return null;
        }
    }

    public String parseDoc(byte[] fileBytes) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(fileBytes);
             HWPFDocument document = new HWPFDocument(bis)) {

            StringBuilder textBuilder = new StringBuilder();
            Range range = document.getRange();
            int numParagraphs = range.numParagraphs();
            for (int i = 0; i < numParagraphs; i++) {
                Paragraph paragraph = range.getParagraph(i);
                String text = paragraph.text().replace("\u0007", "").trim();
                if (!text.isEmpty()) {
                    textBuilder.append(text).append("\n");
                }
            }

            String result = textBuilder.toString().trim();
            log.debug("解析DOC成功，文本长度: {}, 段落数: {}", result.length(), numParagraphs);
            return result;

        } catch (IOException e) {
            log.error("解析DOC失败: {}", e.getMessage(), e);
            throw new RuntimeException("解析DOC文件失败: " + e.getMessage(), e);
        }
    }

    public String parseDocx(byte[] fileBytes) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(fileBytes);
             XWPFDocument document = new XWPFDocument(bis)) {

            List<XWPFParagraph> paragraphs = document.getParagraphs();
            StringBuilder textBuilder = new StringBuilder();

            for (XWPFParagraph paragraph : paragraphs) {
                String text = paragraph.getText();
                if (text != null && !text.trim().isEmpty()) {
                    textBuilder.append(text).append("\n");
                }
            }

            String result = textBuilder.toString().trim();
            log.debug("解析DOCX成功，文本长度: {}", result.length());
            return result;

        } catch (IOException e) {
            log.error("解析DOCX失败: {}", e.getMessage(), e);
            throw new RuntimeException("解析DOCX文件失败: " + e.getMessage(), e);
        }
    }

    public String parsePdf(byte[] fileBytes) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(fileBytes);
             PDDocument document = PDDocument.load(bis)) {

            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);

            log.debug("解析PDF成功，文本长度: {}", text.length());
            return text.trim();

        } catch (IOException e) {
            log.error("解析PDF失败: {}", e.getMessage(), e);
            throw new RuntimeException("解析PDF文件失败: " + e.getMessage(), e);
        }
    }

    public String parseTxt(byte[] fileBytes) {
        try {
            String text = new String(fileBytes, StandardCharsets.UTF_8);
            log.debug("解析TXT成功，文本长度: {}", text.length());
            return text.trim();
        } catch (Exception e) {
            try {
                String text = new String(fileBytes, "GBK");
                log.debug("解析TXT(GBK)成功，文本长度: {}", text.length());
                return text.trim();
            } catch (Exception ex) {
                log.error("解析TXT失败: {}", ex.getMessage(), ex);
                throw new RuntimeException("解析TXT文件失败: " + ex.getMessage(), ex);
            }
        }
    }

    public List<List<String>> parseExcel(byte[] fileBytes) {
        List<List<String>> result = new ArrayList<>();
        try (ByteArrayInputStream bis = new ByteArrayInputStream(fileBytes)) {
            List<Object> rows = EasyExcel.read(bis).headRowNumber(0).sheet().doReadSync();
            List<String> sheetData = new ArrayList<>();
            for (Object row : rows) {
                if (row instanceof Map) {
                    Map<Integer, String> rowMap = (Map<Integer, String>) row;
                    StringBuilder rowBuilder = new StringBuilder();
                    for (Map.Entry<Integer, String> entry : rowMap.entrySet()) {
                        if (rowBuilder.length() > 0) {
                            rowBuilder.append("\t");
                        }
                        rowBuilder.append(entry.getValue() != null ? entry.getValue() : "");
                    }
                    sheetData.add(rowBuilder.toString().trim());
                }
            }
            result.add(sheetData);
            log.debug("解析Excel成功，sheet数据行数: {}", sheetData.size());
            return result;
        } catch (Exception e) {
            log.error("解析Excel失败: {}", e.getMessage(), e);
            throw new RuntimeException("解析Excel文件失败: " + e.getMessage(), e);
        }
    }
}