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
import java.util.HashMap;
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
            String bodyText = extractDocxBodyWithNumbering(fileBytes);
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

    private String extractDocxBodyWithNumbering(byte[] fileBytes) {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(fileBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if ("word/document.xml".equals(name)
                        || "word/numbering.xml".equals(name)
                        || "word/styles.xml".equals(name)) {
                    entries.put(name, readStream(zip));
                }
            }

            byte[] documentXml = entries.get("word/document.xml");
            if (documentXml != null) {
                return parseDocxXmlWithNumbering(
                        documentXml,
                        entries.get("word/numbering.xml"),
                        entries.get("word/styles.xml"));
            }
        } catch (Exception e) {
            log.warn("DOCX编号正文提取失败: {}", e.getMessage());
        }
        return extractDocxBody(fileBytes);
    }

    private String parseDocxXmlWithNumbering(byte[] documentXmlBytes, byte[] numberingXmlBytes, byte[] stylesXmlBytes) throws Exception {
        org.w3c.dom.Document doc = parseXml(documentXmlBytes);
        XPath xpath = createWordXPath();
        NumberingData numbering = parseNumbering(numberingXmlBytes, stylesXmlBytes);

        org.w3c.dom.NodeList paragraphs = (org.w3c.dom.NodeList)
                xpath.evaluate("//w:p", doc, XPathConstants.NODESET);

        StringBuilder result = new StringBuilder();
        Map<String, List<Integer>> counters = new HashMap<>();
        List<Integer> outlineCounters = new ArrayList<>();
        for (int i = 0; i < paragraphs.getLength(); i++) {
            org.w3c.dom.Node p = paragraphs.item(i);
            String paraText = readParagraphText(p);
            NumberingRef numberingRef = getParagraphNumbering(p, xpath, numbering);
            String prefix = formatNumberPrefix(numbering, numberingRef, counters);
            if (prefix.isEmpty()) {
                prefix = formatOutlineNumberPrefix(getParagraphOutlineLevel(p, xpath, numbering), outlineCounters);
            }
            String content = joinNumberPrefix(prefix, paraText);

            String trimmed = content.trim();
            if (!trimmed.isEmpty()) {
                if (result.length() > 0) {
                    result.append("\n");
                }
                result.append(trimmed);
            }
        }
        return result.toString();
    }

    private org.w3c.dom.Document parseXml(byte[] xmlBytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xmlBytes));
    }

    private XPath createWordXPath() {
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
        return xpath;
    }

    private NumberingData parseNumbering(byte[] numberingXmlBytes, byte[] stylesXmlBytes) throws Exception {
        NumberingData data = new NumberingData();
        XPath xpath = createWordXPath();

        if (numberingXmlBytes != null) {
            org.w3c.dom.Document numberingDoc = parseXml(numberingXmlBytes);
            org.w3c.dom.NodeList abstractNums = (org.w3c.dom.NodeList)
                    xpath.evaluate("//w:abstractNum", numberingDoc, XPathConstants.NODESET);
            for (int i = 0; i < abstractNums.getLength(); i++) {
                org.w3c.dom.Node abstractNum = abstractNums.item(i);
                String abstractId = attr(abstractNum, "abstractNumId");
                if (abstractId.isEmpty()) {
                    continue;
                }

                AbstractNumbering abstractNumbering = new AbstractNumbering();
                abstractNumbering.numStyleLink = attr((org.w3c.dom.Node) xpath.evaluate("./w:numStyleLink", abstractNum, XPathConstants.NODE), "val");
                abstractNumbering.styleLink = attr((org.w3c.dom.Node) xpath.evaluate("./w:styleLink", abstractNum, XPathConstants.NODE), "val");
                org.w3c.dom.NodeList levels = (org.w3c.dom.NodeList)
                        xpath.evaluate("./w:lvl", abstractNum, XPathConstants.NODESET);
                for (int j = 0; j < levels.getLength(); j++) {
                    org.w3c.dom.Node lvl = levels.item(j);
                    NumberingLevel level = new NumberingLevel();
                    level.ilvl = valueOrDefault(attr(lvl, "ilvl"), "0");
                    level.start = parseInt(valueOrDefault(attr((org.w3c.dom.Node) xpath.evaluate("./w:start", lvl, XPathConstants.NODE), "val"), "1"), 1);
                    level.numFmt = valueOrDefault(attr((org.w3c.dom.Node) xpath.evaluate("./w:numFmt", lvl, XPathConstants.NODE), "val"), "decimal");
                    level.lvlText = valueOrDefault(attr((org.w3c.dom.Node) xpath.evaluate("./w:lvlText", lvl, XPathConstants.NODE), "val"),
                            "%" + (parseInt(level.ilvl, 0) + 1) + ".");
                    level.pStyle = attr((org.w3c.dom.Node) xpath.evaluate("./w:pStyle", lvl, XPathConstants.NODE), "val");
                    abstractNumbering.levels.put(level.ilvl, level);
                }
                data.abstractNums.put(abstractId, abstractNumbering);
            }

            org.w3c.dom.NodeList nums = (org.w3c.dom.NodeList)
                    xpath.evaluate("/w:numbering/w:num", numberingDoc, XPathConstants.NODESET);
            for (int i = 0; i < nums.getLength(); i++) {
                org.w3c.dom.Node num = nums.item(i);
                String numId = attr(num, "numId");
                String abstractId = attr((org.w3c.dom.Node) xpath.evaluate("./w:abstractNumId", num, XPathConstants.NODE), "val");
                if (!numId.isEmpty() && !abstractId.isEmpty()) {
                    NumberingInstance instance = new NumberingInstance();
                    instance.numId = numId;
                    instance.abstractId = abstractId;
                    data.nums.put(numId, instance);
                }
            }

            for (Map.Entry<String, NumberingInstance> entry : data.nums.entrySet()) {
                AbstractNumbering abstractNumbering = data.abstractNums.get(entry.getValue().abstractId);
                if (abstractNumbering == null) {
                    continue;
                }
                for (Map.Entry<String, NumberingLevel> levelEntry : abstractNumbering.levels.entrySet()) {
                    NumberingLevel level = levelEntry.getValue();
                    if (!level.pStyle.isEmpty() && !data.styleNumbering.containsKey(level.pStyle)) {
                        data.styleNumbering.put(level.pStyle, new NumberingRef(entry.getKey(), levelEntry.getKey()));
                    }
                }
            }
        }

        if (stylesXmlBytes != null) {
            org.w3c.dom.Document stylesDoc = parseXml(stylesXmlBytes);
            org.w3c.dom.NodeList styles = (org.w3c.dom.NodeList)
                    xpath.evaluate("//w:style[@w:type='paragraph' or @w:type='numbering']", stylesDoc, XPathConstants.NODESET);
            for (int i = 0; i < styles.getLength(); i++) {
                org.w3c.dom.Node style = styles.item(i);
                String styleId = attr(style, "styleId");
                if (styleId.isEmpty()) {
                    continue;
                }
                StyleNumbering styleNumbering = new StyleNumbering();
                styleNumbering.numId = attr((org.w3c.dom.Node) xpath.evaluate("./w:pPr/w:numPr/w:numId", style, XPathConstants.NODE), "val");
                styleNumbering.ilvl = attr((org.w3c.dom.Node) xpath.evaluate("./w:pPr/w:numPr/w:ilvl", style, XPathConstants.NODE), "val");
                styleNumbering.basedOn = attr((org.w3c.dom.Node) xpath.evaluate("./w:basedOn", style, XPathConstants.NODE), "val");
                styleNumbering.outlineLevel = attr((org.w3c.dom.Node) xpath.evaluate("./w:pPr/w:outlineLvl", style, XPathConstants.NODE), "val");
                styleNumbering.name = attr((org.w3c.dom.Node) xpath.evaluate("./w:name", style, XPathConstants.NODE), "val");
                data.styleRules.put(styleId, styleNumbering);
            }
        }

        return data;
    }

    private NumberingRef getParagraphNumbering(org.w3c.dom.Node paragraph, XPath xpath, NumberingData numbering) throws Exception {
        String styleId = attr((org.w3c.dom.Node) xpath.evaluate("./w:pPr/w:pStyle", paragraph, XPathConstants.NODE), "val");
        String numId = attr((org.w3c.dom.Node) xpath.evaluate("./w:pPr/w:numPr/w:numId", paragraph, XPathConstants.NODE), "val");
        String ilvl = attr((org.w3c.dom.Node) xpath.evaluate("./w:pPr/w:numPr/w:ilvl", paragraph, XPathConstants.NODE), "val");

        if (numId.isEmpty() && !styleId.isEmpty()) {
            NumberingRef styleRef = resolveStyleNumbering(numbering, styleId, new HashMap<String, Boolean>());
            if (styleRef != null) {
                numId = styleRef.numId;
                ilvl = styleRef.ilvl;
            }
        }

        if (numId.isEmpty() && !styleId.isEmpty() && numbering.styleNumbering.containsKey(styleId)) {
            NumberingRef styleRef = numbering.styleNumbering.get(styleId);
            numId = styleRef.numId;
            ilvl = styleRef.ilvl;
        }

        if (!numId.isEmpty() && ilvl.isEmpty() && !styleId.isEmpty()) {
            ilvl = inferNumberLevel(numbering, numId, styleId);
        }

        return numId.isEmpty() ? null : new NumberingRef(numId, ilvl.isEmpty() ? "0" : ilvl);
    }

    private int getParagraphOutlineLevel(org.w3c.dom.Node paragraph, XPath xpath, NumberingData numbering) throws Exception {
        String outline = attr((org.w3c.dom.Node) xpath.evaluate("./w:pPr/w:outlineLvl", paragraph, XPathConstants.NODE), "val");
        if (!outline.isEmpty()) {
            return parseInt(outline, -1);
        }

        String styleId = attr((org.w3c.dom.Node) xpath.evaluate("./w:pPr/w:pStyle", paragraph, XPathConstants.NODE), "val");
        return getStyleOutlineLevel(numbering, styleId, new HashMap<String, Boolean>());
    }

    private int getStyleOutlineLevel(NumberingData numbering, String styleId, Map<String, Boolean> seen) {
        if (styleId == null || styleId.isEmpty() || seen.containsKey(styleId)) {
            return -1;
        }
        seen.put(styleId, true);

        StyleNumbering rule = numbering.styleRules.get(styleId);
        if (rule != null && !rule.outlineLevel.isEmpty()) {
            return parseInt(rule.outlineLevel, -1);
        }

        int levelFromName = inferOutlineLevelFromStyleName(styleId, rule == null ? "" : rule.name);
        if (levelFromName >= 0) {
            return levelFromName;
        }

        return rule == null ? -1 : getStyleOutlineLevel(numbering, rule.basedOn, seen);
    }

    private int inferOutlineLevelFromStyleName(String styleId, String styleName) {
        String value = ((styleId == null ? "" : styleId) + " " + (styleName == null ? "" : styleName)).toLowerCase();
        for (int i = 1; i <= 9; i++) {
            if (value.contains("heading" + i)
                    || value.contains("heading " + i)
                    || value.contains("标题" + i)
                    || value.contains("標題" + i)) {
                return i - 1;
            }
        }
        return -1;
    }

    private String formatOutlineNumberPrefix(int outlineLevel, List<Integer> counters) {
        if (outlineLevel < 0 || outlineLevel > 8) {
            return "";
        }

        for (int i = 0; i < outlineLevel; i++) {
            while (counters.size() <= i) {
                counters.add(0);
            }
            if (counters.get(i) == 0) {
                counters.set(i, 1);
            }
        }
        while (counters.size() <= outlineLevel) {
            counters.add(0);
        }
        counters.set(outlineLevel, counters.get(outlineLevel) + 1);
        while (counters.size() > outlineLevel + 1) {
            counters.remove(counters.size() - 1);
        }

        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i <= outlineLevel; i++) {
            if (i > 0) {
                prefix.append('.');
            }
            prefix.append(counters.get(i));
        }
        prefix.append(". ");
        return prefix.toString();
    }

    private NumberingRef resolveStyleNumbering(NumberingData numbering, String styleId, Map<String, Boolean> seen) {
        if (styleId == null || styleId.isEmpty() || seen.containsKey(styleId)) {
            return null;
        }
        seen.put(styleId, true);

        StyleNumbering rule = numbering.styleRules.get(styleId);
        if (rule == null) {
            return null;
        }
        if (!rule.numId.isEmpty()) {
            String ilvl = rule.ilvl.isEmpty() ? inferNumberLevel(numbering, rule.numId, styleId) : rule.ilvl;
            return new NumberingRef(rule.numId, ilvl.isEmpty() ? "0" : ilvl);
        }
        return resolveStyleNumbering(numbering, rule.basedOn, seen);
    }

    private String inferNumberLevel(NumberingData numbering, String numId, String styleId) {
        NumberingInstance instance = numbering.nums.get(numId);
        AbstractNumbering abstractNumbering = instance == null ? null : resolveAbstractNumbering(numbering, instance.abstractId, new HashMap<String, Boolean>());
        if (abstractNumbering == null) {
            return "0";
        }
        for (Map.Entry<String, NumberingLevel> entry : abstractNumbering.levels.entrySet()) {
            if (styleId.equals(entry.getValue().pStyle)) {
                return entry.getKey();
            }
        }
        return "0";
    }

    private String formatNumberPrefix(NumberingData numbering, NumberingRef ref, Map<String, List<Integer>> counters) {
        if (ref == null) {
            return "";
        }
        NumberingInstance instance = numbering.nums.get(ref.numId);
        AbstractNumbering abstractNumbering = instance == null ? null : resolveAbstractNumbering(numbering, instance.abstractId, new HashMap<String, Boolean>());
        if (abstractNumbering == null) {
            return "";
        }

        int ilvl = parseInt(ref.ilvl, 0);
        NumberingLevel level = abstractNumbering.levels.get(String.valueOf(ilvl));
        if (level == null) {
            return "";
        }

        List<Integer> counter = counters.get(ref.numId);
        if (counter == null) {
            counter = new ArrayList<>();
            counters.put(ref.numId, counter);
        }
        for (int i = 0; i <= ilvl; i++) {
            while (counter.size() <= i) {
                counter.add(null);
            }
            if (counter.get(i) == null) {
                NumberingLevel currentLevel = abstractNumbering.levels.get(String.valueOf(i));
                counter.set(i, (currentLevel == null ? 1 : currentLevel.start) - 1);
            }
        }
        counter.set(ilvl, counter.get(ilvl) + 1);
        while (counter.size() > ilvl + 1) {
            counter.remove(counter.size() - 1);
        }

        String prefix = level.lvlText;
        for (int i = 1; i <= 9; i++) {
            int index = i - 1;
            Integer value = index < counter.size() ? counter.get(index) : null;
            NumberingLevel refLevel = abstractNumbering.levels.get(String.valueOf(index));
            if (refLevel == null) {
                refLevel = level;
            }
            prefix = prefix.replace("%" + i, value == null ? "" : formatNumberValue(value, refLevel.numFmt));
        }
        return prefix.isEmpty() || Character.isWhitespace(prefix.charAt(prefix.length() - 1)) ? prefix : prefix + " ";
    }

    private String formatNumberValue(int value, String numFmt) {
        if ("upperLetter".equals(numFmt) || "lowerLetter".equals(numFmt)) {
            int n = value;
            StringBuilder text = new StringBuilder();
            while (n > 0) {
                n -= 1;
                text.insert(0, (char) ('A' + (n % 26)));
                n = n / 26;
            }
            String result = text.toString();
            return "lowerLetter".equals(numFmt) ? result.toLowerCase() : result;
        }
        if ("upperRoman".equals(numFmt) || "lowerRoman".equals(numFmt)) {
            String roman = toRoman(value);
            return "lowerRoman".equals(numFmt) ? roman.toLowerCase() : roman;
        }
        return String.valueOf(value);
    }

    private AbstractNumbering resolveAbstractNumbering(NumberingData numbering, String abstractId, Map<String, Boolean> seen) {
        if (abstractId == null || abstractId.isEmpty() || seen.containsKey(abstractId)) {
            return null;
        }
        seen.put(abstractId, true);

        AbstractNumbering abstractNumbering = numbering.abstractNums.get(abstractId);
        if (abstractNumbering == null) {
            return null;
        }
        if (!abstractNumbering.levels.isEmpty()) {
            return abstractNumbering;
        }

        String linkedStyleId = !abstractNumbering.numStyleLink.isEmpty()
                ? abstractNumbering.numStyleLink
                : abstractNumbering.styleLink;
        StyleNumbering linkedStyle = linkedStyleId.isEmpty() ? null : numbering.styleRules.get(linkedStyleId);
        NumberingInstance linkedInstance = linkedStyle == null || linkedStyle.numId.isEmpty()
                ? null
                : numbering.nums.get(linkedStyle.numId);
        return linkedInstance == null
                ? abstractNumbering
                : resolveAbstractNumbering(numbering, linkedInstance.abstractId, seen);
    }

    private String toRoman(int value) {
        int[] amounts = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        int n = value;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < amounts.length; i++) {
            while (n >= amounts[i]) {
                result.append(symbols[i]);
                n -= amounts[i];
            }
        }
        return result.toString();
    }

    private String joinNumberPrefix(String prefix, String content) {
        if (prefix == null || prefix.isEmpty()) {
            return content;
        }
        String trimmedPrefix = prefix.trim();
        String trimmedContent = content.replaceFirst("^\\s+", "");
        return trimmedContent.startsWith(trimmedPrefix) ? content : prefix + content;
    }

    private String readParagraphText(org.w3c.dom.Node node) {
        StringBuilder result = new StringBuilder();
        appendParagraphText(node, result);
        return result.toString();
    }

    private void appendParagraphText(org.w3c.dom.Node node, StringBuilder result) {
        String localName = node.getLocalName();
        if ("t".equals(localName)) {
            result.append(node.getTextContent());
            return;
        }
        if ("tab".equals(localName)) {
            result.append('\t');
            return;
        }
        if ("br".equals(localName) || "cr".equals(localName)) {
            result.append('\n');
            return;
        }

        org.w3c.dom.Node child = node.getFirstChild();
        while (child != null) {
            appendParagraphText(child, result);
            child = child.getNextSibling();
        }
    }

    private String attr(org.w3c.dom.Node node, String name) {
        if (node == null || node.getAttributes() == null) {
            return "";
        }
        org.w3c.dom.Node attr = node.getAttributes().getNamedItemNS(
                "http://schemas.openxmlformats.org/wordprocessingml/2006/main", name);
        if (attr == null) {
            attr = node.getAttributes().getNamedItem("w:" + name);
        }
        if (attr == null) {
            attr = node.getAttributes().getNamedItem(name);
        }
        return attr == null ? "" : attr.getNodeValue();
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isEmpty() ? defaultValue : value;
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static class NumberingData {
        private final Map<String, AbstractNumbering> abstractNums = new HashMap<>();
        private final Map<String, NumberingInstance> nums = new HashMap<>();
        private final Map<String, NumberingRef> styleNumbering = new HashMap<>();
        private final Map<String, StyleNumbering> styleRules = new HashMap<>();
    }

    private static class AbstractNumbering {
        private final Map<String, NumberingLevel> levels = new HashMap<>();
        private String numStyleLink = "";
        private String styleLink = "";
    }

    private static class NumberingInstance {
        private String numId;
        private String abstractId;
    }

    private static class NumberingLevel {
        private String ilvl;
        private int start;
        private String numFmt;
        private String lvlText;
        private String pStyle;
    }

    private static class NumberingRef {
        private final String numId;
        private final String ilvl;

        private NumberingRef(String numId, String ilvl) {
            this.numId = numId;
            this.ilvl = ilvl;
        }
    }

    private static class StyleNumbering {
        private String numId = "";
        private String ilvl = "";
        private String basedOn = "";
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
