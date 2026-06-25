package com.smartdoc.service;

import com.alibaba.excel.EasyExcel;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.config.TikaConfig;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class DocumentParserService {

    private Tika tika;

    @PostConstruct
    void init() {
        try {
            InputStream configStream = getClass().getResourceAsStream("/tika-config.xml");
            if (configStream != null) {
                TikaConfig config = new TikaConfig(configStream);
                tika = new Tika(config);
                log.info("Tika已加载自定义配置 (tika-config.xml)");
            } else {
                tika = new Tika();
                log.warn("未找到 tika-config.xml，使用默认配置");
            }
        } catch (Exception e) {
            log.warn("加载 Tika 配置失败，使用默认配置: {}", e.getMessage());
            tika = new Tika();
        }
    }

    public String parseDocument(byte[] fileBytes, String fileType) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new IllegalArgumentException("文件内容为空");
        }

        try (InputStream is = new ByteArrayInputStream(fileBytes)) {
            String text = tika.parseToString(is);
            log.debug("Tika解析成功，文本长度: {}", text.length());
            return text.trim();
        } catch (Exception e) {
            log.error("Tika解析失败: {}", e.getMessage(), e);
            throw new RuntimeException("解析文档失败: " + e.getMessage(), e);
        }
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