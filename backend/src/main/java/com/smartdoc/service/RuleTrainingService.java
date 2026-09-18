package com.smartdoc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdoc.dto.RuleDto;
import com.smartdoc.dto.RuleTrainingCandidateDto;
import com.smartdoc.dto.RuleTrainingResponseDto;
import com.smartdoc.entity.ApiConfig;
import com.smartdoc.template.PromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleTrainingService {

    private final ApiConfigService apiConfigService;
    private final RuleGroupService ruleGroupService;
    private final PromptOverrideService promptOverrideService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${smartdoc.audit.timeout:120}")
    private int timeout;

    public RuleTrainingResponseDto trainRules(String reviewReport, String auditMode, String groupId) {
        ApiConfig apiConfig = apiConfigService.getRawApiConfig();
        if (apiConfig == null || apiConfig.getEndpoint() == null || apiConfig.getEndpoint().trim().isEmpty()) {
            throw new IllegalArgumentException("请先配置AI API");
        }

        String scope = normalizeScope(auditMode);
        String existingRules = buildExistingRulesContext(groupId, scope);
        String content = callLLM(buildTrainingPrompt(reviewReport, scope, existingRules), apiConfig, scope);
        JsonNode root = parseRoot(content);

        return RuleTrainingResponseDto.builder()
                .rules(parseRules(root, scope))
                .duplicateHints(parseDuplicateHints(root))
                .build();
    }

    /**
     * 训练范围：brief=变更简报总结规则 / ticket=工单审核规则 / document=文档审核规则
     */
    private String normalizeScope(String auditMode) {
        if ("brief".equalsIgnoreCase(auditMode)) {
            return "brief";
        }
        return "ticket".equalsIgnoreCase(auditMode) ? "ticket" : "document";
    }

    /**
     * 组装训练提示词：优先使用用户在界面上自定义的内容（prompt_override 表），
     * 未自定义时回退到内置模板；两者都做同样的占位符替换。
     */
    private String buildTrainingPrompt(String reviewReport, String scope, String existingRules) {
        Map<String, String> params = new HashMap<>();
        params.put("auditScope", scope);
        params.put("reviewReport", reviewReport);
        params.put("existingRules", existingRules);
        // 变更简报（brief）用总结规则专用模板，其余沿用审核规则模板
        String template = "brief".equals(scope) ? "rule-training-brief-user" : "rule-training-user";
        String custom = promptOverrideService.findOverrideOrNull(template);
        if (custom != null) {
            log.info("规则训练使用自定义提示词: {} ({} 字)", template, custom.length());
            return PromptTemplate.formatText(custom, params);
        }
        return PromptTemplate.format(template, params);
    }

    private String buildExistingRulesContext(String groupId, String scope) {
        if (groupId == null || groupId.trim().isEmpty()) {
            return "当前未指定规则组。";
        }
        try {
            List<RuleDto> rules = ruleGroupService.getRulesByGroupId(groupId, scope);
            if (rules == null || rules.isEmpty()) {
                return "当前规则组暂无已有规则。";
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < rules.size(); i++) {
                RuleDto rule = rules.get(i);
                sb.append(i + 1)
                        .append(". ")
                        .append(rule.getName() != null ? rule.getName() : "")
                        .append(" | 级别: ")
                        .append(rule.getSeverity() != null ? rule.getSeverity() : "warning")
                        .append("\n")
                        .append(rule.getPrompt() != null ? rule.getPrompt() : "")
                        .append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("加载规则组已有规则失败，规则训练将不进行重复规则上下文判断: {}", e.getMessage());
            return "当前规则组已有规则加载失败。";
        }
    }

    private String callLLM(String prompt, ApiConfig apiConfig, String scope) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", apiConfig.getModel());
        requestBody.put("temperature", 0.1);

        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> systemMessage = new HashMap<>();
        systemMessage.put("role", "system");
        String systemTemplate = "brief".equals(scope) ? "rule-training-brief-system" : "rule-training-system";
        systemMessage.put("content", PromptTemplate.format(systemTemplate, null));
        messages.add(systemMessage);

        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);
        requestBody.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiConfig.getApiKey() != null && !apiConfig.getApiKey().trim().isEmpty()) {
            headers.setBearerAuth(apiConfig.getApiKey());
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout * 1000);
        factory.setReadTimeout(timeout * 1000);

        ResponseEntity<String> response = new RestTemplate(factory).exchange(
                apiConfig.getEndpoint(),
                HttpMethod.POST,
                new HttpEntity<>(requestBody, headers),
                String.class
        );
        return extractContent(response.getBody());
    }

    private String extractContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.size() == 0) {
                throw new IllegalArgumentException("AI响应缺少choices");
            }
            String content = choices.get(0).path("message").path("content").asText();
            if (content == null || content.trim().isEmpty()) {
                throw new IllegalArgumentException("AI返回内容为空");
            }
            return repairJson(content);
        } catch (Exception e) {
            throw new RuntimeException("解析AI响应失败: " + e.getMessage(), e);
        }
    }

    private JsonNode parseRoot(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (Exception e) {
            log.warn("解析规则训练结果失败: {}", e.getMessage());
            throw new RuntimeException("AI生成的规则格式无效，请重试", e);
        }
    }

    private List<RuleTrainingCandidateDto> parseRules(JsonNode root, String scope) {
        List<RuleTrainingCandidateDto> rules = new ArrayList<>();
        JsonNode rulesNode = root.path("rules");
        if (!rulesNode.isArray()) {
            return rules;
        }

        for (JsonNode node : rulesNode) {
            String name = trimToLength(node.path("name").asText(""), 100);
            String prompt = node.path("prompt").asText("");
            if (name.isEmpty() || prompt.trim().isEmpty()) {
                continue;
            }
            rules.add(RuleTrainingCandidateDto.builder()
                    .name(name)
                    .riskType(node.path("riskType").asText(""))
                    .sourceInsight(node.path("sourceInsight").asText(""))
                    .generalizedRisk(node.path("generalizedRisk").asText(""))
                    .triggerScenario(node.path("triggerScenario").asText(""))
                    .prompt(prompt.trim())
                    // 变更简报的总结规则没有严重级别概念，统一置为 info
                    .severity("brief".equals(scope) ? "info" : normalizeSeverity(node.path("severity").asText("warning")))
                    .passExample(node.path("passExample").asText(""))
                    .failExample(node.path("failExample").asText(""))
                    .auditScope(scope)
                    .build());
        }
        return rules;
    }

    private List<String> parseDuplicateHints(JsonNode root) {
        List<String> hints = new ArrayList<>();
        JsonNode duplicateNode = root.path("duplicateHints");
        if (!duplicateNode.isArray()) {
            return hints;
        }
        for (JsonNode node : duplicateNode) {
            String value = node.asText("");
            if (value != null && !value.trim().isEmpty()) {
                hints.add(value.trim());
            }
        }
        return hints;
    }

    private String repairJson(String content) {
        String result = content.trim();
        Matcher matcher = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```").matcher(result);
        if (matcher.find()) {
            result = matcher.group(1).trim();
        }
        int firstBrace = result.indexOf('{');
        int lastBrace = result.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            result = result.substring(firstBrace, lastBrace + 1);
        }
        return result.replaceAll(",\\s*([}\\]])", "$1");
    }

    private String normalizeSeverity(String severity) {
        if ("error".equalsIgnoreCase(severity)) return "error";
        if ("info".equalsIgnoreCase(severity)) return "info";
        return "warning";
    }

    private String trimToLength(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

}
