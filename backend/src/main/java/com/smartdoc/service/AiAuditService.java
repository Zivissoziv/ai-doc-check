package com.smartdoc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdoc.template.PromptTemplate;
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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.HttpURLConnection;
import java.io.IOException;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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

    private final ObjectMapper objectMapper;

    public AiAuditService() {
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

        Map<Integer, AuditResultDto> skippedMap = new HashMap<>();
        List<Rule> resolvableRules = new ArrayList<>();
        Map<String, Object> safeUserData = userData != null ? userData : new HashMap<>();
        for (int i = 0; i < enabledRules.size(); i++) {
            Rule rule = enabledRules.get(i);
            if (hasDataVarReference(rule.getPrompt())) {
                List<String> missing = getMissingDataVars(rule.getPrompt(), safeUserData);
                if (!missing.isEmpty()) {
                    log.info("跳过规则 '{}': 缺少数据变量 {}", rule.getRuleName(), missing);
                    skippedMap.put(i, buildSkippedResult(rule, missing));
                    continue;
                }
            }
            String triggerCondition = rule.getTriggerCondition();
            if (triggerCondition != null && !triggerCondition.trim().isEmpty()) {
                try {
                    String refVarPath = TriggerConditionEvaluator.getReferencedVarPath(triggerCondition);
                    if (refVarPath != null) {
                        String[] parts = refVarPath.split("\\.");
                        Object refValue = resolveDataValue(safeUserData, parts, 0);
                        if (refValue == null || (refValue instanceof String && ((String) refValue).isEmpty())) {
                            log.info("跳过规则 '{}': 触发条件缺少数据变量 {}", rule.getRuleName(), refVarPath);
                            skippedMap.put(i, buildTriggerConditionSkipped(rule, "触发条件缺少数据变量：data." + refVarPath));
                            continue;
                        }
                    }
                    log.debug("评估规则 '{}' 触发条件: {}", rule.getRuleName(), triggerCondition);
                    boolean conditionMet = TriggerConditionEvaluator.evaluate(triggerCondition, safeUserData);
                    if (!conditionMet) {
                        log.info("跳过规则 '{}': 触发条件不满足 {}", rule.getRuleName(), triggerCondition);
                        skippedMap.put(i, buildTriggerConditionSkipped(rule, "触发条件不满足：" + triggerCondition));
                        continue;
                    }
                } catch (IllegalArgumentException e) {
                    log.warn("规则 '{}' 触发条件评估失败: {}", rule.getRuleName(), e.getMessage());
                    skippedMap.put(i, buildTriggerConditionSkipped(rule, "触发条件评估失败: " + e.getMessage()));
                    continue;
                }
            }
            resolvableRules.add(rule);
        }

        List<AuditResultDto> auditResults;
        if (!resolvableRules.isEmpty()) {
            log.info("开始AI审核，规则数量: {}, 文档长度: {}", resolvableRules.size(), documentText.length());
            if (batchSize > 0 && resolvableRules.size() > batchSize) {
                auditResults = performBatchAudit(resolvableRules, documentText, apiConfig, userData, repeatPrompt, batchSize);
            } else {
                auditResults = performSingleAudit(resolvableRules, documentText, apiConfig, userData, repeatPrompt);
            }
        } else {
            auditResults = new ArrayList<>();
        }

        return mergeInOriginalOrder(enabledRules.size(), skippedMap, auditResults);
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

            try {
                String prompt = buildBatchPrompt(batchRules, documentText, userData, repeatPrompt);
                String response = callLLM(prompt, apiConfig, defaultTemperature);

                List<AuditResultDto> batchResults = parseAuditResults(response, batchRules);
                
                for (int i = 0; i < batchResults.size(); i++) {
                    Rule rule = batchRules.get(i);
                    batchResults.get(i).setRuleId(rule.getId() != null ? rule.getId().intValue() : startIdx + i);
                }
                
                allResults.addAll(batchResults);
            } catch (Exception e) {
                log.error("第 {} 批审核失败: {}", batchIndex + 1, e.getMessage(), e);
                for (int i = 0; i < batchRules.size(); i++) {
                    allResults.add(buildErrorResult(batchRules.get(i), startIdx + i, "审核失败: " + e.getMessage()));
                }
            }

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

    public void performAuditStreaming(List<Rule> rules, String documentText,
                                       ApiConfig apiConfig, Map<String, Object> userData,
                                       boolean repeatPrompt, int batchSize,
                                       Consumer<Map.Entry<Integer, AuditResultDto>> resultCallback) {
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

        Map<Integer, AuditResultDto> skippedMap = new HashMap<>();
        List<Rule> resolvableRules = new ArrayList<>();
        Map<String, Object> safeUserData = userData != null ? userData : new HashMap<>();
        for (int i = 0; i < enabledRules.size(); i++) {
            Rule rule = enabledRules.get(i);
            if (hasDataVarReference(rule.getPrompt())) {
                List<String> missing = getMissingDataVars(rule.getPrompt(), safeUserData);
                if (!missing.isEmpty()) {
                    log.info("跳过规则 '{}': 缺少数据变量 {}", rule.getRuleName(), missing);
                    skippedMap.put(i, buildSkippedResult(rule, missing));
                    continue;
                }
            }
            String triggerCondition = rule.getTriggerCondition();
            if (triggerCondition != null && !triggerCondition.trim().isEmpty()) {
                try {
                    String refVarPath = TriggerConditionEvaluator.getReferencedVarPath(triggerCondition);
                    if (refVarPath != null) {
                        String[] parts = refVarPath.split("\\.");
                        Object refValue = resolveDataValue(safeUserData, parts, 0);
                        if (refValue == null || (refValue instanceof String && ((String) refValue).isEmpty())) {
                            log.info("跳过规则 '{}': 触发条件缺少数据变量 {}", rule.getRuleName(), refVarPath);
                            skippedMap.put(i, buildTriggerConditionSkipped(rule, "触发条件缺少数据变量：data." + refVarPath));
                            continue;
                        }
                    }
                    log.debug("评估规则 '{}' 触发条件: {}", rule.getRuleName(), triggerCondition);
                    boolean conditionMet = TriggerConditionEvaluator.evaluate(triggerCondition, safeUserData);
                    if (!conditionMet) {
                        log.info("跳过规则 '{}': 触发条件不满足 {}", rule.getRuleName(), triggerCondition);
                        skippedMap.put(i, buildTriggerConditionSkipped(rule, "触发条件不满足：" + triggerCondition));
                        continue;
                    }
                } catch (IllegalArgumentException e) {
                    log.warn("规则 '{}' 触发条件评估失败: {}", rule.getRuleName(), e.getMessage());
                    skippedMap.put(i, buildTriggerConditionSkipped(rule, "触发条件评估失败: " + e.getMessage()));
                    continue;
                }
            }
            resolvableRules.add(rule);
        }

        for (Map.Entry<Integer, AuditResultDto> entry : skippedMap.entrySet()) {
            resultCallback.accept(entry);
        }

        int finalBatchSize = batchSize > 0 ? batchSize : resolvableRules.size();
        int totalBatches = resolvableRules.isEmpty() ? 0 : (resolvableRules.size() + finalBatchSize - 1) / finalBatchSize;

        if (totalBatches > 0) {
            log.info("开始流式AI审核，可审规则: {}, 文档长度: {}", resolvableRules.size(), documentText.length());
        }

        for (int batchIndex = 0; batchIndex < totalBatches; batchIndex++) {
            int startIdx = batchIndex * finalBatchSize;
            int endIdx = Math.min(startIdx + finalBatchSize, resolvableRules.size());
            List<Rule> batchRules = resolvableRules.subList(startIdx, endIdx);

            log.info("流式处理第 {} 批，规则 {}-{}", batchIndex + 1, findGlobalStart(enabledRules.size(), skippedMap, startIdx) + 1,
                    findGlobalStart(enabledRules.size(), skippedMap, endIdx - 1) + 1);

            try {
                String prompt = buildBatchPrompt(batchRules, documentText, userData, repeatPrompt);
                String response = callLLM(prompt, apiConfig, defaultTemperature);

                List<AuditResultDto> batchResults = parseAuditResults(response, batchRules);

                for (int i = 0; i < batchResults.size(); i++) {
                    Rule rule = batchRules.get(i);
                    int globalIdx = findGlobalStart(enabledRules.size(), skippedMap, startIdx + i);
                    batchResults.get(i).setRuleId(rule.getId() != null ? rule.getId().intValue() : globalIdx);
                    resultCallback.accept(new AbstractMap.SimpleEntry<>(globalIdx, batchResults.get(i)));
                }
            } catch (Exception e) {
                log.error("第 {} 批审核失败: {}", batchIndex + 1, e.getMessage(), e);
                for (int i = 0; i < batchRules.size(); i++) {
                    int globalIdx = findGlobalStart(enabledRules.size(), skippedMap, startIdx + i);
                    resultCallback.accept(new AbstractMap.SimpleEntry<>(globalIdx,
                            buildErrorResult(batchRules.get(i), globalIdx, "审核失败: " + e.getMessage())));
                }
            }

            if (batchIndex < totalBatches - 1) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private int findGlobalStart(int totalEnabled, Map<Integer, AuditResultDto> skippedMap, int resolvableIdx) {
        int count = 0;
        for (int i = 0; i < totalEnabled; i++) {
            if (!skippedMap.containsKey(i)) {
                if (count == resolvableIdx) {
                    return i;
                }
                count++;
            }
        }
        return resolvableIdx;
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

        Map<String, String> promptParams = new HashMap<>();
        promptParams.put("documentContent", docContent);
        promptParams.put("rulesList", rulesList.toString());
        promptParams.put("ruleCount", String.valueOf(rules.size()));
        promptParams.put("ruleCountMinusOne", String.valueOf(rules.size() - 1));

        String basePrompt = PromptTemplate.format("audit-user", promptParams);

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
    private static final Pattern SINGLE_QUOTE_PATTERN = Pattern.compile("'([^']*?)'");
    private static final Pattern LEADING_TEXT_PATTERN = Pattern.compile("^[^{]*");

    private String replaceDataVars(String prompt, Map<String, Object> data) {
        Matcher matcher = DATA_VAR_PATTERN.matcher(prompt);
        
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String varPath = matcher.group(1);
            String[] parts = DOT_SPLIT_PATTERN.split(varPath);
            
            try {
                Object value = resolveDataValue(data, parts, 0);
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

    private Object resolveDataValue(Object value, String[] parts, int startIndex) {
        if (value == null) return null;
        for (int i = startIndex; i < parts.length; i++) {
            String part = parts[i];
            if (value instanceof Map) {
                value = ((Map<?, ?>) value).get(part);
            } else if (value instanceof List) {
                List<?> list = (List<?>) value;
                try {
                    int idx = Integer.parseInt(part);
                    if (idx >= 0 && idx < list.size()) {
                        value = list.get(idx);
                    } else {
                        return null;
                    }
                } catch (NumberFormatException e) {
                    List<String> collected = new ArrayList<>();
                    for (Object item : list) {
                        Object itemResult = resolveDataValue(item, parts, i);
                        if (itemResult != null) {
                            collected.add(itemResult.toString());
                        }
                    }
                    return collected.isEmpty() ? null : String.join(", ", collected);
                }
            } else {
                return null;
            }
        }
        return value;
    }

    private boolean hasDataVarReference(String prompt) {
        return DATA_VAR_PATTERN.matcher(prompt).find();
    }

    private List<String> getMissingDataVars(String prompt, Map<String, Object> data) {
        List<String> missing = new ArrayList<>();
        Matcher matcher = DATA_VAR_PATTERN.matcher(prompt);
        while (matcher.find()) {
            String varPath = matcher.group(1);
            String[] parts = DOT_SPLIT_PATTERN.split(varPath);
            Object value = resolveDataValue(data, parts, 0);
            if (value == null || (value instanceof String && ((String) value).isEmpty())) {
                missing.add("{{data." + varPath + "}}");
            }
        }
        return missing;
    }

    private AuditResultDto buildTriggerConditionSkipped(Rule rule, String summary) {
        AuditResultDto result = new AuditResultDto();
        result.setRuleId(rule.getId() != null ? rule.getId().intValue() : 0);
        result.setRuleName(rule.getRuleName());
        result.setSeverity(rule.getSeverity().name().toLowerCase());
        result.setPass(false);
        result.setSkipped(true);
        result.setConfidence(0);
        result.setSummary(summary);
        result.setIssues(null);
        return result;
    }

    private AuditResultDto buildSkippedResult(Rule rule, List<String> missingVars) {
        String missingStr = String.join("、", missingVars);
        AuditResultDto result = new AuditResultDto();
        result.setRuleId(rule.getId() != null ? rule.getId().intValue() : 0);
        result.setRuleName(rule.getRuleName());
        result.setSeverity(rule.getSeverity().name().toLowerCase());
        result.setPass(false);
        result.setSkipped(true);
        result.setConfidence(0);
        result.setSummary("已跳过: 缺少必要数据 " + missingStr);
        result.setIssues(null);
        return result;
    }

    private List<AuditResultDto> mergeInOriginalOrder(int totalCount,
            Map<Integer, AuditResultDto> skippedMap,
            List<AuditResultDto> auditResults) {
        List<AuditResultDto> merged = new ArrayList<>();
        int auditIdx = 0;
        for (int i = 0; i < totalCount; i++) {
            if (skippedMap.containsKey(i)) {
                merged.add(skippedMap.get(i));
            } else {
                merged.add(auditResults.get(auditIdx++));
            }
        }
        return merged;
    }

    /**
     * 可中止请求的 RequestFactory，用于超时重试前主动断开底层 HttpURLConnection。
     */
    private static class AbortableRequestFactory extends SimpleClientHttpRequestFactory {
        private volatile HttpURLConnection connection;

        AbortableRequestFactory(int connectTimeout, int readTimeout) {
            setConnectTimeout(connectTimeout);
            setReadTimeout(readTimeout);
        }

        @Override
        protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
            super.prepareConnection(connection, httpMethod);
            this.connection = connection;
        }

        void abortRequest() {
            HttpURLConnection conn = this.connection;
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private AbortableRequestFactory createAbortableFactory() {
        return new AbortableRequestFactory(timeout * 1000, timeout * 1000);
    }

    private RestTemplate createRestTemplate() {
        return new RestTemplate(createAbortableFactory());
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
        systemMessage.put("content", PromptTemplate.format("audit-system",
                java.util.Collections.singletonMap("auditRole", auditRole)));
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

        int maxRetries = 1;
        int attempt = 0;
        AbortableRequestFactory requestFactory = null;
        while (attempt <= maxRetries) {
            try {
                requestFactory = createAbortableFactory();
                RestTemplate rt = new RestTemplate(requestFactory);
                ResponseEntity<String> response = rt.exchange(
                    endpoint, HttpMethod.POST, entity, String.class
                );
                
                String responseBody = response.getBody();
                log.debug("LLM响应长度: {}", responseBody != null ? responseBody.length() : 0);
                
                String content = extractContent(responseBody);
                
                if (!isValidJson(content)) {
                    log.warn("LLM返回的JSON格式无效，尝试自动修复...");
                    try {
                        String repaired = repairByLLM(content, apiConfig, temperature);
                        if (isValidJson(repaired)) {
                            log.info("自动修复JSON成功");
                            content = repaired;
                        } else {
                            throw new RuntimeException("LLM返回的JSON格式无效，自动修复后仍无法解析");
                        }
                    } catch (Exception repairEx) {
                        log.error("自动修复JSON失败: {}", repairEx.getMessage());
                        throw new RuntimeException("LLM返回的JSON格式无效，自动修复失败", repairEx);
                    }
                }
                
                return content;
                
            } catch (ResourceAccessException e) {
                attempt++;
                // 重试前关闭之前的请求连接
                if (requestFactory != null) {
                    requestFactory.abortRequest();
                    requestFactory = null;
                }
                if (attempt > maxRetries) {
                    log.error("调用LLM超时(已重试{}次): {}", maxRetries, e.getMessage(), e);
                    throw new RuntimeException("调用AI服务超时，请稍后重试", e);
                }
                log.warn("调用LLM超时(第{}次重试): {}", attempt, e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (Exception e) {
                // 非超时异常也需关闭连接
                if (requestFactory != null) {
                    requestFactory.abortRequest();
                }
                log.error("调用LLM失败: {}", e.getMessage(), e);
                throw new RuntimeException("调用AI服务失败: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("调用AI服务失败");
    }

    private String extractContent(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            throw new RuntimeException("LLM返回内容为空");
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            
            if (choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).path("message");
                String content = message.path("content").asText();
                
                if (content == null || content.isEmpty()) {
                    throw new RuntimeException("LLM返回content为空");
                }
                
                content = repairJson(content);

                return content;
            }
            
            throw new RuntimeException("LLM响应格式错误: choices为空");
            
        } catch (JsonProcessingException e) {
            log.error("解析LLM原始响应JSON失败: {}", e.getMessage());
            throw new RuntimeException("解析AI响应失败: " + e.getMessage(), e);
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

        content = LEADING_TEXT_PATTERN.matcher(content).replaceFirst("");

        int lastBrace = content.lastIndexOf('}');
        if (lastBrace >= 0) {
            content = content.substring(0, lastBrace + 1);
        }

        content = SINGLE_QUOTE_PATTERN.matcher(content).replaceAll("\"$1\"");

        content = content.replace("'", "\"");

        content = TRAILING_COMMA_PATTERN.matcher(content).replaceAll("$1");

        content = content.replace("undefined", "null");
        content = NAN_PATTERN.matcher(content).replaceAll("null");
        content = INFINITY_PATTERN.matcher(content).replaceAll("null");
        content = NEG_INFINITY_PATTERN.matcher(content).replaceAll("null");

        content = content.replace("True", "true");
        content = content.replace("False", "false");
        content = content.replace("None", "null");

        return content;
    }

    private boolean isValidJson(String content) {
        try {
            objectMapper.readTree(content);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String repairByLLM(String invalidContent, ApiConfig apiConfig, double temperature) {
        String endpoint = apiConfig.getEndpoint();
        String apiKey = apiConfig.getApiKey();
        String model = apiConfig.getModel();

        String repairPrompt = PromptTemplate.format("repair-user",
                java.util.Collections.singletonMap("invalidContent", invalidContent));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("temperature", Math.min(temperature, 0.1));

        List<Map<String, String>> messages = new ArrayList<>();

        Map<String, String> systemMessage = new HashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", PromptTemplate.format("repair-system", null));
        messages.add(systemMessage);

        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", repairPrompt);
        messages.add(userMessage);

        requestBody.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.setBearerAuth(apiKey);
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        AbortableRequestFactory factory = createAbortableFactory();
        try {
            RestTemplate rt = new RestTemplate(factory);
            ResponseEntity<String> response = rt.exchange(endpoint, HttpMethod.POST, entity, String.class);
            String responseBody = response.getBody();
            return extractContent(responseBody);
        } finally {
            factory.abortRequest();
        }
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

            if (results.size() < rules.size()) {
                for (int i = results.size(); i < rules.size(); i++) {
                    log.warn("结果数量不足，兜底填充规则 {}: {}", i, rules.get(i).getRuleName());
                    results.add(buildErrorResult(rules.get(i), i, "审核结果缺失"));
                }
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
            
            AuditResultDto result = AuditResultDto.builder()
                    .ruleId(rule.getId() != null ? rule.getId().intValue() : index)
                    .ruleName(rule.getRuleName())
                    .severity(rule.getSeverity().name().toLowerCase())
                    .pass(ruleResult.path("pass").asBoolean(false))
                    .confidence(ruleResult.path("confidence").asInt(50))
                    .summary(ruleResult.path("summary").asText("审核完成"))
                    .issues(issues)
                    .build();

            if (!Boolean.TRUE.equals(result.getPass()) && isKeywordMissingResult(result)) {
                result.setSummary("未匹配关键词: " + result.getSummary());
            }

            return result;
        } else {
            return buildErrorResult(rule, index, "未找到审核结果");
        }
    }

    private boolean isKeywordMissingResult(AuditResultDto result) {
        String summary = result.getSummary() != null ? result.getSummary() : "";
        if (containsKeywordMissing(summary)) {
            return true;
        }
        if (result.getIssues() != null) {
            for (AuditIssueDto issue : result.getIssues()) {
                if (issue.getProblem() != null && containsKeywordMissing(issue.getProblem())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsKeywordMissing(String text) {
        return (text.contains("关键字") || text.contains("关键词"))
                && (text.contains("未包含") || text.contains("未找到") || text.contains("缺少") || text.contains("没有") || text.contains("不存在"));
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
