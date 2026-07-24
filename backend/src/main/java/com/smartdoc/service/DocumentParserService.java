package com.smartdoc.service;

import com.alibaba.excel.EasyExcel;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
public class DocumentParserService {

    private Tika tika;

    @PostConstruct
    void init() {
        tika = new Tika();
        log.info("Tika使用默认配置");
    }

    // 编号定义内部类
    private static class NumberingLevelDef {
        int start;
        String numFmt;
        String lvlText;
        NumberingLevelDef(int start, String numFmt, String lvlText) {
            this.start = start;
            this.numFmt = numFmt;
            this.lvlText = lvlText;
        }
    }

    public String parseDocument(byte[] fileBytes, String fileType) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new IllegalArgumentException("文件内容为空");
        }

        // DOCX：优先从 word/document.xml 提取正文，效果与前端 Mammoth.js 一致
        if ("docx".equals(fileType)) {
            String bodyText = extractDocxBody(fileBytes);
            if (bodyText != null && !bodyText.trim().isEmpty()) {
                log.debug("DOCX正文提取成功，文本长度: {}", bodyText.length());
                return bodyText.trim();
            }
            log.warn("DOCX正文提取失败或为空，回退到Tika");
        }

        // 其他类型或 DOCX 回退：使用 Tika
        try (InputStream is = new ByteArrayInputStream(fileBytes)) {
            String text = tika.parseToString(is);
            log.debug("Tika解析成功，文本长度: {}", text.length());
            if (text.trim().isEmpty()) {
                log.warn("Tika解析结果为空，fileType={}, fileBytes长度={}", fileType, fileBytes.length);
            }
            return text.trim();
        } catch (Exception e) {
            log.error("Tika解析失败: {}", e.getMessage(), e);
            throw new RuntimeException("解析文档失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 DOCX 的 word/document.xml 中提取正文文本，支持自动编号。
     */
    private String extractDocxBody(byte[] fileBytes) {
        Map<String, byte[]> zipEntries = new HashMap<>();
        // 先遍历 ZIP，收集需要的文件
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(fileBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                zipEntries.put(entry.getName(), readStream(zip));
            }
        } catch (Exception e) {
            log.warn("DOCX解压失败: {}", e.getMessage());
            return null;
        }

        byte[] docXmlBytes = zipEntries.get("word/document.xml");
        if (docXmlBytes == null) return null;

        byte[] numXmlBytes = zipEntries.get("word/numbering.xml");
        Map<String, Map<String, NumberingLevelDef>> numberingMap = null;
        if (numXmlBytes != null) {
            numberingMap = parseNumbering(numXmlBytes);
        }

        try {
            return parseDocxXml(docXmlBytes, numberingMap);
        } catch (Exception e) {
            log.warn("DOCX XML 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 numbering.xml，返回 numId -> { ilvl -> NumberingLevelDef } 的映射
     */
    private Map<String, Map<String, NumberingLevelDef>> parseNumbering(byte[] numXmlBytes) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(new ByteArrayInputStream(numXmlBytes));

            XPath xpath = XPathFactory.newInstance().newXPath();
            xpath.setNamespaceContext(new NamespaceContext() {
                @Override
                public String getNamespaceURI(String prefix) {
                    return "w".equals(prefix)
                            ? "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                            : null;
                }
                @Override
                public String getPrefix(String namespaceURI) { return null; }
                @Override
                public Iterator getPrefixes(String namespaceURI) { return null; }
            });

            // 解析 <w:num> 映射 numId -> abstractNumId
            org.w3c.dom.NodeList numNodes = (org.w3c.dom.NodeList)
                    xpath.evaluate("//w:num", doc, XPathConstants.NODESET);
            Map<String, String> numToAbstract = new HashMap<>();
            for (int i = 0; i < numNodes.getLength(); i++) {
                org.w3c.dom.Node num = numNodes.item(i);
                String numId = xpath.evaluate("w:numId/@w:val", num);
                String abstractNumId = xpath.evaluate("w:abstractNumId/@w:val", num);
                if (numId != null && abstractNumId != null) {
                    numToAbstract.put(numId, abstractNumId);
                }
            }

            // 解析 <w:abstractNum> 获取 level 定义
            org.w3c.dom.NodeList abstractNumNodes = (org.w3c.dom.NodeList)
                    xpath.evaluate("//w:abstractNum", doc, XPathConstants.NODESET);
            Map<String, Map<String, NumberingLevelDef>> abstractMap = new HashMap<>();
            for (int i = 0; i < abstractNumNodes.getLength(); i++) {
                org.w3c.dom.Node an = abstractNumNodes.item(i);
                String anId = xpath.evaluate("@w:abstractNumId", an);
                if (anId == null || anId.isEmpty()) continue;

                Map<String, NumberingLevelDef> levels = new HashMap<>();
                org.w3c.dom.NodeList lvlNodes = (org.w3c.dom.NodeList)
                        xpath.evaluate("w:lvl", an, XPathConstants.NODESET);
                for (int j = 0; j < lvlNodes.getLength(); j++) {
                    org.w3c.dom.Node lvl = lvlNodes.item(j);
                    String ilvl = xpath.evaluate("@w:ilvl", lvl);
                    if (ilvl == null) ilvl = "0";

                    String startStr = xpath.evaluate("w:start/@w:val", lvl);
                    int start = 1;
                    if (startStr != null && !startStr.isEmpty()) {
                        try { start = Integer.parseInt(startStr); } catch (NumberFormatException e) { start = 1; }
                    }
                    String numFmt = xpath.evaluate("w:numFmt/@w:val", lvl);
                    if (numFmt == null) numFmt = "decimal";
                    String lvlText = xpath.evaluate("w:lvlText/@w:val", lvl);
                    if (lvlText == null) lvlText = "%1.";

                    levels.put(ilvl, new NumberingLevelDef(start, numFmt, lvlText));
                }
                abstractMap.put(anId, levels);
            }

            // 合并成最终 map
            Map<String, Map<String, NumberingLevelDef>> result = new HashMap<>();
            for (Map.Entry<String, String> entry : numToAbstract.entrySet()) {
                Map<String, NumberingLevelDef> levels = abstractMap.get(entry.getValue());
                if (levels != null) {
                    result.put(entry.getKey(), levels);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("解析 numbering.xml 失败: {}", e.getMessage());
            return null;
        }
    }

    private String parseDocxXml(byte[] xmlBytes,
                                Map<String, Map<String, NumberingLevelDef>> numberingMap) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        org.w3c.dom.Document doc = builder.parse(new ByteArrayInputStream(xmlBytes));

        XPath xpath = XPathFactory.newInstance().newXPath();
        xpath.setNamespaceContext(new NamespaceContext() {
            @Override
            public String getNamespaceURI(String prefix) {
                return "w".equals(prefix)
                        ? "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                        : null;
            }
            @Override
            public String getPrefix(String namespaceURI) { return null; }
            @Override
            public Iterator getPrefixes(String namespaceURI) { return null; }
        });

        org.w3c.dom.NodeList paragraphs = (org.w3c.dom.NodeList)
                xpath.evaluate("//w:p", doc, XPathConstants.NODESET);

        // 跟踪每个 (numId, ilvl) 组合的当前编号
        Map<String, Integer> numCounters = new HashMap<>();

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < paragraphs.getLength(); i++) {
            org.w3c.dom.Node p = paragraphs.item(i);
            org.w3c.dom.NodeList tNodes = (org.w3c.dom.NodeList)
                    xpath.evaluate(".//w:t", p, XPathConstants.NODESET);

            StringBuilder paraText = new StringBuilder();
            for (int j = 0; j < tNodes.getLength(); j++) {
                paraText.append(tNodes.item(j).getTextContent());
            }

            // 检查自动编号
            String numPrefix = "";
            if (numberingMap != null) {
                org.w3c.dom.Node numPr = (org.w3c.dom.Node)
                        xpath.evaluate("w:pPr/w:numPr", p, XPathConstants.NODE);
                if (numPr != null) {
                    String numId = xpath.evaluate("w:numId/@w:val", numPr);
                    String ilvl = xpath.evaluate("w:ilvl/@w:val", numPr);
                    if (ilvl == null || ilvl.isEmpty()) ilvl = "0";

                    if (numId != null && !numId.isEmpty()) {
                        String key = numId + "-" + ilvl;
                        Map<String, NumberingLevelDef> levels = numberingMap.get(numId);
                        NumberingLevelDef lvlDef = (levels != null) ? levels.get(ilvl) : null;

                        if (lvlDef != null && !"none".equals(lvlDef.numFmt) && !"bullet".equals(lvlDef.numFmt)) {
                            // 初始化计数器
                            if (!numCounters.containsKey(key)) {
                                numCounters.put(key, lvlDef.start);
                            }
                            int currentNum = numCounters.get(key);
                            numCounters.put(key, currentNum + 1);

                            // 格式化编号
                            String lvlText = (lvlDef.lvlText != null) ? lvlDef.lvlText : "%1.";
                            numPrefix = formatNumbering(lvlText, currentNum);
                        } else {
                            // 即使不显示编号，也要计数以保持序列正确
                            if (!numCounters.containsKey(key)) {
                                numCounters.put(key, 1);
                            } else {
                                numCounters.put(key, numCounters.get(key) + 1);
                            }
                        }
                    }
                }
            }

            String trimmed = paraText.toString().trim();
            if (!trimmed.isEmpty()) {
                if (result.length() > 0) {
                    result.append("\n");
                }
                result.append(numPrefix).append(trimmed);
            }
        }

        return result.toString();
    }

    private String formatNumbering(String lvlText, int currentNum) {
        // 替换 %1, %2 等为当前值
        return lvlText.replaceAll("%\\d+", String.valueOf(currentNum)) + " ";
    }

    private byte[] readStream(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    public String detectFileType(byte[] fileBytes, String providedType) {
        if (providedType != null && !"auto".equals(providedType)) {
            return providedType;
        }

        // 魔数检测
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
            new String(fileBytes, java.nio.charset.StandardCharsets.UTF_8);
            return "txt";
        } catch (Exception e) {
            return null;
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
