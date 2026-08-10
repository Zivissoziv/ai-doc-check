package com.smartdoc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${smartdoc.audit.timeout:120}")
    private int timeout;

    public RuleTrainingResponseDto trainRules(String reviewReport, String auditMode) {
        ApiConfig apiConfig = apiConfigService.getRawApiConfig();
        if (apiConfig == null || apiConfig.getEndpoint() == null || apiConfig.getEndpoint().trim().isEmpty()) {
            throw new IllegalArgumentException("请先配置AI API");
        }

        String scope = "ticket".equalsIgnoreCase(auditMode) ? "ticket" : "document";
        String content = callLLM(buildTrainingPrompt(reviewReport, scope), apiConfig);
        List<RuleTrainingCandidateDto> rules = parseRules(content, scope);
        return RuleTrainingResponseDto.builder().rules(rules).build();
    }

    private String buildTrainingPrompt(String reviewReport, String scope) {
        Map<String, String> params = new HashMap<>();
        params.put("auditScope", scope);
        params.put("reviewReport", reviewReport);
        return PromptTemplate.format("rule-training-user", params);
    }

    private String callLLM(String prompt, ApiConfig apiConfig) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", apiConfig.getModel());
        requestBody.put("temperature", 0.1);

        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> systemMessage = new HashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", PromptTemplate.format("rule-training-system", null));
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

    private List<RuleTrainingCandidateDto> parseRules(String content, String scope) {
        try {
            JsonNode rulesNode = objectMapper.readTree(content).path("rules");
            List<RuleTrainingCandidateDto> rules = new ArrayList<>();
            if (!rulesNode.isArray()) {
                return rules;
            }
            for (JsonNode node : rulesNode) {
                String name = trimToLength(node.path("name").asText(""), 100);
                String prompt = node.path("prompt").asText("");
                if (name.isEmpty() || prompt.trim().isEmpty()) {
                    continue;
                }
                String severity = normalizeSeverity(node.path("severity").asText("warning"));
                rules.add(RuleTrainingCandidateDto.builder()
                        .name(name)
                        .riskType(node.path("riskType").asText(""))
                        .sourceInsight(node.path("sourceInsight").asText(""))
                        .generalizedRisk(node.path("generalizedRisk").asText(""))
                        .triggerScenario(node.path("triggerScenario").asText(""))
                        .prompt(prompt.trim())
                        .severity(severity)
                        .positiveExample(node.path("positiveExample").asText(""))
                        .negativeExample(node.path("negativeExample").asText(""))
                        .confidence(clamp(node.path("confidence").asInt(70), 0, 100))
                        .auditScope(scope)
                        .build());
            }
            return rules;
        } catch (Exception e) {
            log.warn("解析规则训练结果失败: {}", e.getMessage());
            throw new RuntimeException("AI生成的规则格式无效，请重试", e);
        }
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

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
