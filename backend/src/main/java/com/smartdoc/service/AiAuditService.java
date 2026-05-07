package com.smartdoc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdoc.dto.AuditResultDto;
import com.smartdoc.dto.AuditIssueDto;
import com.smartdoc.entity.ApiConfig;
import com.smartdoc.entity.Rule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AiAuditService {

    @Value("${smartdoc.audit.timeout:120}")
    private int timeout;

    @Value("${smartdoc.audit.temperature:0.1}")
    private double defaultTemperature;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public AiAuditService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    public List<AuditResultDto> performAudit(List<Rule> rules, String documentText, 
                                              ApiConfig apiConfig, Map<String, Object> userData,
                                              boolean repeatPrompt, int batchSize) {
        
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("规则列表为空");
        }

        if (documentText == null || documentText.isEmpty()) {
            throw new IllegalArgumentException("文档内容为空");
        }

        List<Rule> enabledRules = rules.stream()
            .filter(r -> r.getIsEnabled() != null && r.getIsEnabled())
            .collect(Collectors.toList());

        if (enabledRules.isEmpty()) {
            throw new IllegalArgumentException("没有启用的规则");
        }

        log.info("开始AI审核，规则数量: {}, 文档长度: {}", enabledRules.size(), documentText.length());

        if (batchSize > 0 && enabledRules.size() > batchSize) {
            return performBatchAudit(enabledRules, documentText, apiConfig, userData, repeatPrompt, batchSize);
        } else {
            return performSingleAudit(enabledRules, documentText, apiConfig, userData, repeatPrompt);
        }
    }

    private List<AuditResultDto> performBatchAudit(List<Rule> rules, String documentText,
                                                    ApiConfig apiConfig, Map<String, Object> userData,
                                                    boolean repeatPrompt, int batchSize) {
        List<AuditResultDto> allResults = new ArrayList<>();
        int totalBatches = (rules.size() + batchSize - 1) / batchSize;

        for (int batchIndex = 0; batchIndex < totalBatches; batchIndex++) {
            int startIdx = batchIndex * batchSize;
            int endIdx = Math.min(startIdx + batchSize, rules.size());
            List<Rule> batchRules = rules.subList(startIdx, endIdx);

            log.info("处理第 {} 批，规则 {}-{}", batchIndex + 1, startIdx + 1, endIdx);

            String prompt = buildBatchPrompt(batchRules, documentText, userData, repeatPrompt);
            String response = callLLM(prompt, apiConfig, defaultTemperature);

            List<AuditResultDto> batchResults = parseAuditResults(response, batchRules);
            
            for (int i = 0; i < batchResults.size(); i++) {
                Rule rule = batchRules.get(i);
                batchResults.get(i).setRuleId(rule.getId() != null ? rule.getId().intValue() : startIdx + i);
            }
            
            allResults.addAll(batchResults);

            if (batchIndex < totalBatches - 1) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        return allResults;
    }

    private List<AuditResultDto> performSingleAudit(List<Rule> rules, String documentText,
                                                     ApiConfig apiConfig, Map<String, Object> userData,
                                                     boolean repeatPrompt) {
        String prompt = buildBatchPrompt(rules, documentText, userData, repeatPrompt);
        String response = callLLM(prompt, apiConfig, defaultTemperature);
        return parseAuditResults(response, rules);
    }

    public String buildBatchPrompt(List<Rule> rules, String documentText, 
                                    Map<String, Object> userData, boolean repeatPrompt) {
        StringBuilder rulesList = new StringBuilder();
        for (int i = 0; i < rules.size(); i++) {
            Rule rule = rules.get(i);
            String prompt = rule.getPrompt();
            
            if (userData != null) {
                prompt = replaceDataVars(prompt, userData);
            }
            
            rulesList.append(String.format("[规则%d] %s (级别: %s)\n%s\n\n", 
                i, rule.getRuleName(), rule.getSeverity().name().toLowerCase(), prompt));
        }

        String docContent = documentText.substring(0, Math.min(documentText.length(), 10000));
        
        String basePrompt = "你需要对以下文档进行批量审核，按照给定的规则逐一检查。\n\n" +
            "文档内容：\n" + docContent + "\n\n" +
            "审核规则列表：\n" + rulesList.toString() + "\n" +
            "=== 输出格式要求 ===\n" +
            "你必须返回一个合法的JSON对象，格式如下：\n" +
            "{\n" +
            "  \"results\": [\n" +
            "    {\n" +
            "      \"ruleId\": 0,\n" +
            "      \"pass\": true,\n" +
            "      \"confidence\": 95,\n" +
            "      \"issues\": [],\n" +
            "      \"summary\": \"文档格式规范，符合要求\"\n" +
            "    },\n" +
            "    {\n" +
            "      \"ruleId\": 1,\n" +
            "      \"pass\": false,\n" +
            "      \"confidence\": 85,\n" +
            "      \"issues\": [\n" +
            "        {\n" +
            "          \"location\": \"第3章第2节\",\n" +
            "          \"problem\": \"缺少必要的参数说明\",\n" +
            "          \"suggestion\": \"建议补充参数列表和类型定义\"\n" +
            "        }\n" +
            "      ],\n" +
            "      \"summary\": \"发现1处问题，建议修改\"\n" +
            "    }\n" +
            "  ]\n" +
            "}\n\n" +
            "=== 字段说明 ===\n" +
            "- ruleId: 规则序号，对应规则列表中的序号(0-" + (rules.size() - 1) + ")\n" +
            "- pass: 是否通过，true或false\n" +
            "- confidence: 置信度，0-100的整数\n" +
            "- issues: 问题列表，通过时为[]，不通过时包含具体对象\n" +
            "- summary: 总体评价，简短描述\n\n" +
            "=== 重要约束 ===\n" +
            "1. 必须返回合法JSON，不要添加markdown代码块标记\n" +
            "2. results数组长度必须等于" + rules.size() + "\n" +
            "3. 每个规则都要有对应的result对象\n" +
            "4. issues数组为空时写成 [] 而不是 null\n" +
            "5. 字符串使用双引号，不要使用单引号\n" +
            "6. 最后一个元素后面不要加逗号\n" +
            "7. 不要包含任何解释说明文字，只返回JSON";

        if (repeatPrompt) {
            return basePrompt + "\n\n--- 重复提示（请仔细阅读以上内容）---\n\n" + basePrompt;
        }
        
        return basePrompt;
    }

    private static final Pattern DATA_VAR_PATTERN = Pattern.compile("\\{\\{data\\.([^}]+)\\}\\}");
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```");
    private static final Pattern TRAILING_COMMA_PATTERN = Pattern.compile(",\\s*([}\\]])");
    private static final Pattern NAN_PATTERN = Pattern.compile("\\bNaN\\b");
    private static final Pattern INFINITY_PATTERN = Pattern.compile("\\bInfinity\\b");
    private static final Pattern NEG_INFINITY_PATTERN = Pattern.compile("\\b-Infinity\\b");
    private static final Pattern DOT_SPLIT_PATTERN = Pattern.compile("\\.");

    private String replaceDataVars(String prompt, Map<String, Object> data) {
        Matcher matcher = DATA_VAR_PATTERN.matcher(prompt);
        
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String varPath = matcher.group(1);
            String[] parts = DOT_SPLIT_PATTERN.split(varPath);
            
            Object value = data;
            try {
                for (String part : parts) {
                    if (value instanceof Map) {
                        value = ((Map<?, ?>) value).get(part);
                    } else {
                        value = null;
                        break;
                    }
                }
                
                if (value == null) {
                    matcher.appendReplacement(result, matcher.group(0));
                } else {
                    matcher.appendReplacement(result, value.toString());
                }
            } catch (Exception e) {
                matcher.appendReplacement(result, matcher.group(0));
            }
        }
        matcher.appendTail(result);
        
        return result.toString();
    }

    private String callLLM(String prompt, ApiConfig apiConfig, double temperature) {
        String endpoint = apiConfig.getEndpoint();
        String apiKey = apiConfig.getApiKey();
        String model = apiConfig.getModel();
        String auditRole = apiConfig.getAuditRole();

        if (endpoint == null || endpoint.isEmpty()) {
            throw new IllegalArgumentException("API endpoint 未配置");
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("temperature", temperature);
        
        List<Map<String, String>> messages = new ArrayList<>();
        
        Map<String, String> systemMessage = new HashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", auditRole + "，你擅长发现文档中的结构、逻辑和合规问题。请严格按照要求的JSON格式返回结果，不要添加任何额外说明。");
        messages.add(systemMessage);
        
        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);
        
        requestBody.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.setBearerAuth(apiKey);
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                endpoint, HttpMethod.POST, entity, String.class
            );
            
            String responseBody = response.getBody();
            log.debug("LLM响应长度: {}", responseBody != null ? responseBody.length() : 0);
            
            return extractContent(responseBody);
            
        } catch (Exception e) {
            log.error("调用LLM失败: {}", e.getMessage(), e);
            throw new RuntimeException("调用AI服务失败: " + e.getMessage(), e);
        }
    }

    private String extractContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            
            if (choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).path("message");
                String content = message.path("content").asText();
                
                content = repairJson(content);
                
                return content;
            }
            
            throw new RuntimeException("LLM响应格式错误");
            
        } catch (Exception e) {
            log.error("解析LLM响应失败: {}", e.getMessage(), e);
            throw new RuntimeException("解析AI响应失败: " + e.getMessage(), e);
        }
    }

    private String repairJson(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        content = content.trim();

        Matcher codeBlockMatcher = CODE_BLOCK_PATTERN.matcher(content);
        if (codeBlockMatcher.find()) {
            content = codeBlockMatcher.group(1).trim();
        }

        content = TRAILING_COMMA_PATTERN.matcher(content).replaceAll("$1");
        
        content = content.replace("undefined", "null");
        content = NAN_PATTERN.matcher(content).replaceAll("null");
        content = INFINITY_PATTERN.matcher(content).replaceAll("null");
        content = NEG_INFINITY_PATTERN.matcher(content).replaceAll("null");

        return content;
    }

    private List<AuditResultDto> parseAuditResults(String content, List<Rule> rules) {
        List<AuditResultDto> results = new ArrayList<>();
        
        try {
            JsonNode root = objectMapper.readTree(content);
            JsonNode resultsArray = root.path("results");
            
            if (!resultsArray.isArray()) {
                throw new RuntimeException("results字段不是数组");
            }

            for (int i = 0; i < rules.size(); i++) {
                Rule rule = rules.get(i);
                
                JsonNode ruleResult = null;
                for (JsonNode node : resultsArray) {
                    if (node.path("ruleId").asInt() == i) {
                        ruleResult = node;
                        break;
                    }
                }
                
                if (ruleResult == null && i < resultsArray.size()) {
                    ruleResult = resultsArray.get(i);
                }
                
                AuditResultDto result = buildAuditResult(rule, ruleResult, i);
                results.add(result);
            }
            
        } catch (Exception e) {
            log.error("解析审核结果失败: {}", e.getMessage(), e);
            
            for (int i = 0; i < rules.size(); i++) {
                Rule rule = rules.get(i);
                AuditResultDto result = buildErrorResult(rule, i, e.getMessage());
                results.add(result);
            }
        }
        
        return results;
    }

    private AuditResultDto buildAuditResult(Rule rule, JsonNode ruleResult, int index) {
        if (ruleResult != null) {
            List<AuditIssueDto> issues = new ArrayList<>();
            JsonNode issuesArray = ruleResult.path("issues");
            if (issuesArray.isArray()) {
                for (JsonNode issueNode : issuesArray) {
                    issues.add(AuditIssueDto.builder()
                            .location(issueNode.path("location").asText(""))
                            .problem(issueNode.path("problem").asText(""))
                            .suggestion(issueNode.path("suggestion").asText(""))
                            .build());
                }
            }
            
            return AuditResultDto.builder()
                    .ruleId(rule.getId() != null ? rule.getId().intValue() : index)
                    .ruleName(rule.getRuleName())
                    .severity(rule.getSeverity().name().toLowerCase())
                    .pass(ruleResult.path("pass").asBoolean(false))
                    .confidence(ruleResult.path("confidence").asInt(50))
                    .summary(ruleResult.path("summary").asText("审核完成"))
                    .issues(issues)
                    .build();
        } else {
            return buildErrorResult(rule, index, "未找到审核结果");
        }
    }

    private AuditResultDto buildErrorResult(Rule rule, int index, String errorMessage) {
        List<AuditIssueDto> issues = new ArrayList<>();
        issues.add(AuditIssueDto.builder()
                .location("解析")
                .problem("结果解析失败")
                .suggestion("请重试")
                .build());
        
        return AuditResultDto.builder()
                .ruleId(rule.getId() != null ? rule.getId().intValue() : index)
                .ruleName(rule.getRuleName())
                .severity(rule.getSeverity().name().toLowerCase())
                .pass(false)
                .confidence(30)
                .summary("解析失败: " + errorMessage)
                .issues(issues)
                .build();
    }
}