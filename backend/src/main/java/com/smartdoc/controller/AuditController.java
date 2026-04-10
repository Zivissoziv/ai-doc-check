package com.smartdoc.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdoc.dto.*;
import com.smartdoc.entity.ApiConfig;
import com.smartdoc.entity.Rule;
import com.smartdoc.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuditController {

    private final RuleGroupService ruleGroupService;
    private final DocumentParserService documentParserService;
    private final AiAuditService aiAuditService;
    private final ApiConfigService apiConfigService;
    private final ObjectMapper objectMapper;

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
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "缺少 ruleGroupId 参数");
            return ResponseEntity.badRequest().body(errorResponse);
        }

        List<RuleDto> ruleDtos = ruleGroupService.getRulesByGroupId(request.getRuleGroupId());
        if (ruleDtos.isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "规则组为空");
            return ResponseEntity.badRequest().body(errorResponse);
        }

        List<Rule> rules = convertDtosToRules(ruleDtos);

        byte[] documentBytes = null;
        String documentType = request.getDocumentType();

        if (request.getDocumentUrl() != null) {
            try {
                documentBytes = downloadFile(request.getDocumentUrl());
            } catch (Exception e) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", "无法下载文档: " + e.getMessage());
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorResponse);
            }
        } else if (request.getDocumentBase64() != null) {
            try {
                documentBytes = Base64.getDecoder().decode(request.getDocumentBase64());
            } catch (Exception e) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", "documentBase64 格式错误");
                return ResponseEntity.badRequest().body(errorResponse);
            }
        } else {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "缺少 documentUrl 或 documentBase64 参数");
            return ResponseEntity.badRequest().body(errorResponse);
        }

        if (documentType == null || "auto".equals(documentType)) {
            documentType = documentParserService.detectFileType(documentBytes, "auto");
        }

        String documentText;
        try {
            documentText = documentParserService.parseDocument(documentBytes, documentType);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "无法解析文档内容: " + e.getMessage());
            return ResponseEntity.unprocessableEntity().body(errorResponse);
        }

        Map<String, Object> userData = request.getData() != null 
            ? objectMapper.convertValue(request.getData(), Map.class) 
            : new HashMap<>();

        if (request.getExcelUrl() != null || request.getExcelBase64() != null) {
            byte[] excelBytes = null;
            if (request.getExcelUrl() != null) {
                try {
                    excelBytes = downloadFile(request.getExcelUrl());
                } catch (Exception e) {
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", "无法下载Excel: " + e.getMessage());
                    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorResponse);
                }
            } else {
                try {
                    excelBytes = Base64.getDecoder().decode(request.getExcelBase64());
                } catch (Exception e) {
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", "excelBase64 格式错误");
                    return ResponseEntity.badRequest().body(errorResponse);
                }
            }

            try {
                List<List<String>> excelData = documentParserService.parseExcel(excelBytes);
                userData.put("sheets", excelData);
            } catch (Exception e) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", "无法解析Excel文件: " + e.getMessage());
                return ResponseEntity.unprocessableEntity().body(errorResponse);
            }
        }

        ApiConfig apiConfig = apiConfigService.getRawApiConfig();
        if (apiConfig == null) {
            apiConfig = ApiConfig.builder()
                    .endpoint("https://api.deepseek.com/v1/chat/completions")
                    .model("deepseek-chat")
                    .auditRole("专业文档审核专家")
                    .build();
        }

        AuditSettingsDto settings = request.getSettings();
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

        boolean repeatPrompt = settings != null && settings.getRepeatPrompt() != null 
            ? settings.getRepeatPrompt() : true;
        int batchSize = settings != null && settings.getBatchSize() != null 
            ? settings.getBatchSize() : 0;

        try {
            List<AuditResultDto> results = aiAuditService.performAudit(
                rules, documentText, apiConfig, userData, repeatPrompt, batchSize
            );

            Map<String, Object> successResponse = new HashMap<>();
            successResponse.put("success", true);
            successResponse.put("results", results);
            return ResponseEntity.ok(successResponse);

        } catch (Exception e) {
            log.error("审核失败: {}", e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
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
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            if (apiKey != null && !apiKey.isEmpty()) {
                headers.setBearerAuth(apiKey);
            }

            org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
            org.springframework.http.HttpEntity<Map<String, Object>> entity = 
                new org.springframework.http.HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                endpoint, org.springframework.http.HttpMethod.POST, entity, String.class
            );

            return ResponseEntity.ok(response.getBody());

        } catch (Exception e) {
            log.error("代理请求失败: {}", e.getMessage());
            return ResponseEntity.internalServerError().body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private byte[] downloadFile(String url) {
        org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
        ResponseEntity<byte[]> response = restTemplate.getForEntity(url, byte[].class);
        return response.getBody();
    }

    private List<Rule> convertDtosToRules(List<RuleDto> dtos) {
        return dtos.stream()
                .map(dto -> Rule.builder()
                        .ruleName(dto.getName())
                        .prompt(dto.getPrompt())
                        .severity(Rule.Severity.valueOf(dto.getSeverity().toUpperCase()))
                        .isEnabled(dto.getEnabled() != null ? dto.getEnabled() : true)
                        .sortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0)
                        .build())
                .collect(java.util.stream.Collectors.toList());
    }
}