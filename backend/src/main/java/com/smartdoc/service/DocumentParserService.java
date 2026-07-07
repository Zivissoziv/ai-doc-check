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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
     * 从 DOCX 的 word/document.xml 中提取正文文本（与前端 Mammoth.js 方式一致）。
     * 只提取 <w:p> 段落中 <w:t> 元素的文本，排除页眉/页脚/批注等。
     */
    private String extractDocxBody(byte[] fileBytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(fileBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    byte[] xmlBytes = readStream(zip);
                    return parseDocxXml(xmlBytes);
                }
            }
        } catch (Exception e) {
            log.warn("DOCX解压失败: {}", e.getMessage());
        }
        return null;
    }

    private String parseDocxXml(byte[] xmlBytes) throws Exception {
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

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < paragraphs.getLength(); i++) {
            org.w3c.dom.Node p = paragraphs.item(i);
            org.w3c.dom.NodeList tNodes = (org.w3c.dom.NodeList)
                    xpath.evaluate(".//w:t", p, XPathConstants.NODESET);

            StringBuilder paraText = new StringBuilder();
            for (int j = 0; j < tNodes.getLength(); j++) {
                paraText.append(tNodes.item(j).getTextContent());
            }

            String trimmed = paraText.toString().trim();
            if (!trimmed.isEmpty()) {
                if (result.length() > 0) {
                    result.append("\n");
                }
                result.append(trimmed);
            }
        }

        return result.toString();
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
