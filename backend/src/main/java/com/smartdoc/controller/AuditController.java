package com.smartdoc.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdoc.dto.AuditRequestDto;
import com.smartdoc.dto.AuditResultDto;
import com.smartdoc.dto.AuditSettingsDto;
import com.smartdoc.dto.RuleDto;
import com.smartdoc.dto.RuleGroupDto;
import com.smartdoc.entity.ApiConfig;
import com.smartdoc.entity.Rule;
import com.smartdoc.service.AiAuditService;
import com.smartdoc.service.ApiConfigService;
import com.smartdoc.service.AuditFeedbackService;
import com.smartdoc.service.AuditTicketRecordService;
import com.smartdoc.service.DocumentParserService;
import com.smartdoc.service.RuleGroupService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuditController {

    private final RuleGroupService ruleGroupService;
    private final DocumentParserService documentParserService;
    private final AiAuditService aiAuditService;
    private final ApiConfigService apiConfigService;
    private final AuditFeedbackService auditFeedbackService;
    private final AuditTicketRecordService auditTicketRecordService;
    private final ObjectMapper objectMapper;

    private static final Pattern[] SECTION_TITLE_PATTERNS = {
        Pattern.compile("^[第一二三四五六七八九十零百千万]+[章节条款].*"),
        Pattern.compile("^\\d+[\\.、].*"),
        Pattern.compile("^[（(][一二三四五六七八九十]+[)）].*"),
        Pattern.compile("^[一二三四五六七八九十]+[、.].*")
    };

    @GetMapping("/rules")
    public ResponseEntity<Map<String, Object>> getRulesIndex() {
        List<RuleGroupDto> groups = ruleGroupService.getAllRuleGroups();
        
        Map<String, Object> response = new HashMap<>();
        response.put("groups", groups);
        
        RuleGroupDto defaultGroup = ruleGroupService.getDefaultRuleGroup();
        if (defaultGroup != null) {
            response.put("defaultGroup", defaultGroup.getGroupId());
        }
        
        return ResponseEntity.ok(response);
    }

    @PostMapping("/audit")
    public ResponseEntity<Map<String, Object>> performAudit(@RequestBody AuditRequestDto request) {
        log.info("收到审核请求，规则组: {}", request.getRuleGroupId());

        if (request.getRuleGroupId() == null || request.getRuleGroupId().isEmpty()) {
            return badRequest("缺少 ruleGroupId 参数");
        }

        List<Rule> rules = loadRules(request.getRuleGroupId());
        if (rules == null) {
            return badRequest("规则组为空");
        }

        byte[] documentBytes = resolveDocument(request);
        if (documentBytes == null) {
            return badRequest("缺少 documentUrl 或 documentBase64 参数");
        }

        String documentType = resolveDocumentType(request, documentBytes);
        String documentText = parseDocument(documentBytes, documentType);
        if (documentText == null) {
            return ResponseEntity.unprocessableEntity().body(errorMap("无法解析文档内容"));
        }

        Map<String, Object> userData = resolveUserData(request);
        if (userData == null) {
            return ResponseEntity.unprocessableEntity().body(errorMap("无法解析Excel文件"));
        }

        ApiConfig apiConfig = resolveApiConfig(request.getSettings());

        try {
            List<AuditResultDto> results = aiAuditService.performAudit(
                rules, documentText, apiConfig, userData,
                isRepeatPrompt(request.getSettings()),
                getBatchSize(request.getSettings())
            );

            List<com.smartdoc.entity.AuditFeedback> savedFeedbacks = auditFeedbackService.saveAuditResults(results, request.getRuleGroupId());

            // 如果请求携带了 ticketId 和 ts，保存映射关系
            if (request.getTicketId() != null && request.getTs() != null
                    && !request.getTicketId().isEmpty() && !request.getTs().isEmpty()) {
                String batchNo = savedFeedbacks != null && !savedFeedbacks.isEmpty()
                        ? savedFeedbacks.get(0).getAuditBatchNo()
                        : java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                auditTicketRecordService.saveRecord(
                        request.getTicketId(), request.getTs(),
                        batchNo,
                        "ticket_" + request.getTicketId());
            }

            Map<String, Object> successResponse = new HashMap<>();
            successResponse.put("success", true);
            successResponse.put("results", results);
            return ResponseEntity.ok(successResponse);

        } catch (Exception e) {
            log.error("审核失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(errorMap(e.getMessage()));
        }
    }

    @PostMapping("/audit/stream")
    public ResponseEntity<StreamingResponseBody> performAuditStream(@RequestBody AuditRequestDto request) {
        log.info("收到流式审核请求，规则组: {}", request.getRuleGroupId());

        if (request.getRuleGroupId() == null || request.getRuleGroupId().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        List<Rule> rules = loadRules(request.getRuleGroupId());
        if (rules == null) {
            return ResponseEntity.badRequest().build();
        }

        byte[] documentBytes = resolveDocument(request);
        if (documentBytes == null) {
            return ResponseEntity.badRequest().build();
        }

        String documentType = resolveDocumentType(request, documentBytes);
        String documentText = parseDocument(documentBytes, documentType);
        if (documentText == null) {
            return ResponseEntity.unprocessableEntity().build();
        }

        Map<String, Object> userData = resolveUserData(request);
        if (userData == null) {
            return ResponseEntity.unprocessableEntity().build();
        }

        ApiConfig apiConfig = resolveApiConfig(request.getSettings());

        StreamingResponseBody streamBody = outputStream -> {
            try {
                aiAuditService.performAuditStreaming(
                    rules, documentText, apiConfig, userData,
                    isRepeatPrompt(request.getSettings()), getBatchSize(request.getSettings()),
                    entry -> {
                        try {
                            int idx = entry.getKey();
                            AuditResultDto result = entry.getValue();
                            Map<String, Object> entryMap = new HashMap<>();
                            entryMap.put("index", idx);
                            entryMap.put("result", result);
                            String json = objectMapper.writeValueAsString(entryMap);
                            outputStream.write((json + "\n").getBytes(StandardCharsets.UTF_8));
                            outputStream.flush();
                        } catch (Exception e) {
                            log.error("流式写入结果失败", e);
                        }
                    }
                );

                outputStream.flush();
            } catch (Exception e) {
                log.error("流式审核失败", e);
                try {
                    Map<String, Object> errorMap = new HashMap<>();
                    errorMap.put("error", "流式审核失败: " + e.getMessage());
                    String errorJson = objectMapper.writeValueAsString(errorMap);
                    outputStream.write((errorJson + "\n").getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                } catch (Exception ignored) {
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_NDJSON)
                .body(streamBody);
    }

    private List<Rule> loadRules(String groupId) {
        List<RuleDto> ruleDtos = ruleGroupService.getRulesByGroupId(groupId);
        if (ruleDtos.isEmpty()) {
            return null;
        }
        return convertDtosToRules(ruleDtos);
    }

    private byte[] resolveDocument(AuditRequestDto request) {
        if (request.getDocumentUrl() != null) {
            try {
                return downloadFile(request.getDocumentUrl());
            } catch (Exception e) {
                log.error("无法下载文档: {}", e.getMessage(), e);
                return null;
            }
        } else if (request.getDocumentBase64() != null) {
            try {
                return Base64.getDecoder().decode(request.getDocumentBase64());
            } catch (Exception e) {
                log.error("documentBase64解码失败: {}", e.getMessage(), e);
                return null;
            }
        }
        return null;
    }

    private String resolveDocumentType(AuditRequestDto request, byte[] documentBytes) {
        String documentType = request.getDocumentType();
        if (documentType == null || "auto".equals(documentType)) {
            documentType = documentParserService.detectFileType(documentBytes, "auto");
        }
        return documentType;
    }

    private String parseDocument(byte[] documentBytes, String documentType) {
        try {
            return documentParserService.parseDocument(documentBytes, documentType);
        } catch (Exception e) {
            log.error("无法解析文档内容: {}", e.getMessage(), e);
            return null;
        }
    }

    private Map<String, Object> resolveUserData(AuditRequestDto request) {
        Map<String, Object> userData = request.getData() != null 
            ? objectMapper.convertValue(request.getData(), Map.class) 
            : new HashMap<>();

        if (request.getExcelUrl() != null || request.getExcelBase64() != null) {
            byte[] excelBytes;
            if (request.getExcelUrl() != null) {
                try {
                    excelBytes = downloadFile(request.getExcelUrl());
                } catch (Exception e) {
                    log.error("无法下载Excel: {}", e.getMessage(), e);
                    return null;
                }
            } else {
                try {
                    excelBytes = Base64.getDecoder().decode(request.getExcelBase64());
                } catch (Exception e) {
                    log.error("excelBase64解码失败: {}", e.getMessage(), e);
                    return null;
                }
            }

            try {
                List<List<String>> excelData = documentParserService.parseExcel(excelBytes);
                userData.put("sheets", excelData);
            } catch (Exception e) {
                log.error("无法解析Excel文件: {}", e.getMessage(), e);
                return null;
            }
        }
        return userData;
    }

    private ApiConfig resolveApiConfig(AuditSettingsDto settings) {
        ApiConfig apiConfig = apiConfigService.getRawApiConfig();
        if (apiConfig == null) {
            apiConfig = ApiConfig.builder()
                    .endpoint("https://api.deepseek.com/v1/chat/completions")
                    .model("deepseek-chat")
                    .auditRole("专业文档审核专家")
                    .build();
        }

        if (settings != null) {
            if (settings.getEndpoint() != null) {
                apiConfig.setEndpoint(settings.getEndpoint());
            }
            if (settings.getApiKey() != null) {
                apiConfig.setApiKey(settings.getApiKey());
            }
            if (settings.getModel() != null) {
                apiConfig.setModel(settings.getModel());
            }
            if (settings.getAuditRole() != null) {
                apiConfig.setAuditRole(settings.getAuditRole());
            }
        }
        return apiConfig;
    }

    private boolean isRepeatPrompt(AuditSettingsDto settings) {
        return settings != null && settings.getRepeatPrompt() != null 
            ? settings.getRepeatPrompt() : true;
    }

    private int getBatchSize(AuditSettingsDto settings) {
        return settings != null && settings.getBatchSize() != null 
            ? settings.getBatchSize() : 0;
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(errorMap(message));
    }

    private Map<String, Object> errorMap(String message) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", message);
        return errorResponse;
    }

    @PostMapping("/proxy")
    public ResponseEntity<String> proxyRequest(@RequestBody Map<String, Object> request) {
        String endpoint = (String) request.get("endpoint");
        Map<String, Object> body = (Map<String, Object>) request.get("body");
        String apiKey = (String) request.get("apiKey");

        if (endpoint == null || endpoint.isEmpty() || apiKey == null || apiKey.isEmpty()) {
            ApiConfig config = apiConfigService.getRawApiConfig();
            if (config != null) {
                if (endpoint == null || endpoint.isEmpty()) {
                    endpoint = config.getEndpoint();
                }
                if (apiKey == null || apiKey.isEmpty()) {
                    apiKey = config.getApiKey();
                }
            }
        }

        if (endpoint == null) {
            return ResponseEntity.badRequest().body("{\"error\":\"缺少 endpoint 参数\"}");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (apiKey != null && !apiKey.isEmpty()) {
                headers.setBearerAuth(apiKey);
            }

            RestTemplate restTemplate = new RestTemplate();
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                endpoint, HttpMethod.POST, entity, String.class
            );

            return ResponseEntity.ok(response.getBody());

        } catch (Exception e) {
            log.error("代理请求失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("{\"error\":\"代理请求失败\"}");
        }
    }

    private byte[] downloadFile(String url) {
        String lowerUrl = url.toLowerCase();
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
            throw new RuntimeException("不支持的URL协议");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30000);
        factory.setReadTimeout(120000);
        RestTemplate restTemplate = new RestTemplate(factory);
        ResponseEntity<byte[]> response = restTemplate.getForEntity(url, byte[].class);
        return response.getBody();
    }

    private List<Rule> convertDtosToRules(List<RuleDto> dtos) {
        return dtos.stream()
                .map(dto -> Rule.builder()
                        .id(dto.getId())
                        .ruleName(dto.getName())
                        .prompt(dto.getPrompt())
                        .severity(Rule.Severity.valueOf(dto.getSeverity().toUpperCase()))
                        .isEnabled(dto.getEnabled() != null ? dto.getEnabled() : true)
                        .sortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0)
                        .triggerCondition(dto.getTriggerCondition())
                        .build())
                .collect(Collectors.toList());
    }

    @PostMapping("/parse")
    public ResponseEntity<Map<String, Object>> parseDocument(@RequestParam("file") MultipartFile file) {
        log.info("收到文档解析请求，文件名: {}, 大小: {} bytes", file.getOriginalFilename(), file.getSize());

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(errorMap("上传的文件为空"));
        }

        String filename = file.getOriginalFilename();
        String fileType = null;
        if (filename != null) {
            int dotIndex = filename.lastIndexOf('.');
            if (dotIndex > 0) {
                fileType = filename.substring(dotIndex + 1).toLowerCase();
            }
        }

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (Exception e) {
            log.error("读取文件失败: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(errorMap("读取文件失败"));
        }

        String detectedType = documentParserService.detectFileType(fileBytes, fileType);
        if (detectedType == null) {
            return ResponseEntity.badRequest().body(errorMap("无法识别文件类型"));
        }

        String text;
        try {
            text = documentParserService.parseDocument(fileBytes, detectedType);
        } catch (Exception e) {
            log.error("解析文档失败: {}", e.getMessage(), e);
            return ResponseEntity.unprocessableEntity().body(errorMap("解析文档失败"));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("text", text);
        response.put("tree", buildDocumentTree(text));
        response.put("html", buildDocumentHtml(text));
        response.put("fileType", detectedType);
        response.put("filename", filename);

        return ResponseEntity.ok(response);
    }

    private String buildDocumentTree(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        StringBuilder tree = new StringBuilder();
        String[] lines = text.split("\n");
        int sectionCount = 0;
        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()) {
                continue;
            }
            if (isSectionTitle(trimmedLine)) {
                sectionCount++;
                tree.append("├── ").append(trimmedLine).append("\n");
            } else if (sectionCount > 0) {
                tree.append("│   ").append(trimmedLine.substring(0, Math.min(50, trimmedLine.length())));
                if (trimmedLine.length() > 50) {
                    tree.append("...");
                }
                tree.append("\n");
            }
        }
        return tree.toString();
    }

    private boolean isSectionTitle(String line) {
        for (Pattern pattern : SECTION_TITLE_PATTERNS) {
            if (pattern.matcher(line).matches()) {
                return true;
            }
        }
        return false;
    }

    private String buildDocumentHtml(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"document-content\">");
        String[] lines = text.split("\n");
        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()) {
                continue;
            }
            if (isSectionTitle(trimmedLine)) {
                html.append("<h3>").append(escapeHtml(trimmedLine)).append("</h3>");
            } else {
                html.append("<p>").append(escapeHtml(trimmedLine)).append("</p>");
            }
        }
        html.append("</div>");
        return html.toString();
    }

    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
