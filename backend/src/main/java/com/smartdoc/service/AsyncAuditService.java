package com.smartdoc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdoc.dto.AuditResultDto;
import com.smartdoc.dto.AsyncTaskStatusDto;
import com.smartdoc.dto.RuleDto;
import com.smartdoc.entity.ApiConfig;
import com.smartdoc.entity.AuditTicketRecord;
import com.smartdoc.entity.Rule;
import com.smartdoc.mapper.AuditTicketRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncAuditService {

    private static final AtomicInteger threadCounter = new AtomicInteger(0);
    private static final ExecutorService ASYNC_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "async-audit-" + threadCounter.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private final ApiConfigService apiConfigService;
    private final AuditTicketRecordMapper auditTicketRecordMapper;
    private final AuditTicketRecordService auditTicketRecordService;
    private final RuleGroupService ruleGroupService;
    private final DocumentParserService documentParserService;
    private final AiAuditService aiAuditService;
    private final AuditFeedbackService auditFeedbackService;
    private final ObjectMapper objectMapper;

    /**
     * 创建异步审核任务。返回 taskId。
     * 边界处理：
     * - 同一 (ticketId, ts) 已有 PENDING/RUNNING 任务 → 返回已有 taskId
     * - 同一 (ticketId, ts) 已有 COMPLETED → 创建新任务覆盖
     * - 工单系统/LLM 调用失败 → 标记 FAILED
     */
    public String createAsyncTask(String ticketId, String ts, String ruleGroupId) {
        // 检查是否有进行中的任务
        AuditTicketRecord existing = auditTicketRecordMapper.findByTicketIdAndTs(ticketId, ts);
        if (existing != null && existing.getTaskId() != null
                && (AuditTicketRecord.STATUS_PENDING.equals(existing.getStatus())
                 || AuditTicketRecord.STATUS_RUNNING.equals(existing.getStatus()))) {
            log.info("异步任务已存在，返回已有 taskId={}", existing.getTaskId());
            return existing.getTaskId();
        }

        String taskId = UUID.randomUUID().toString();

        if (existing != null) {
            // 已有记录（同步审核或已完成/失败的异步任务），更新为新的异步任务
            // 注意：不覆盖 auditBatchNo，保留历史审核结果，直到新异步任务完成
            existing.setTaskId(taskId);
            existing.setStatus(AuditTicketRecord.STATUS_PENDING);
            existing.setErrorMessage(null);
            auditTicketRecordMapper.updateById(existing);
        } else {
            // 全新记录
            AuditTicketRecord record = AuditTicketRecord.builder()
                    .ticketId(ticketId)
                    .ts(ts)
                    .taskId(taskId)
                    .status(AuditTicketRecord.STATUS_PENDING)
                    .build();
            auditTicketRecordMapper.insert(record);
        }

        log.info("创建异步审核任务: taskId={}, ticketId={}, ts={}", taskId, ticketId, ts);

        // 异步执行审核
        ASYNC_EXECUTOR.submit(() -> doAsyncAudit(taskId, ticketId, ts, ruleGroupId));

        return taskId;
    }

    /** 查询任务状态 */
    public AsyncTaskStatusDto getTaskStatus(String taskId) {
        AuditTicketRecord record = auditTicketRecordMapper.findByTaskId(taskId);
        if (record == null) {
            return null;
        }
        return AsyncTaskStatusDto.builder()
                .taskId(record.getTaskId())
                .status(record.getStatus())
                .errorMessage(record.getErrorMessage())
                .auditBatchNo(record.getAuditBatchNo())
                .build();
    }

    /** 异步审核执行体 */
    private void doAsyncAudit(String taskId, String ticketId, String ts, String ruleGroupId) {
        // 标记 RUNNING
        updateStatus(taskId, AuditTicketRecord.STATUS_RUNNING, null);

        try {
            // 1. 从工单系统获取文档信息
            ApiConfig config = apiConfigService.getRawApiConfig();
            if (config == null || config.getTicketEndpoint() == null || config.getTicketEndpoint().isEmpty()) {
                throw new RuntimeException("工单服务未配置");
            }

            Map<String, Object> ticketInfo = fetchTicketInfo(config, ticketId);
            String documentName = (String) ticketInfo.getOrDefault("documentName", "ticket_" + ticketId);

            // 2. 下载文档
            byte[] documentBytes;
            if (ticketInfo.get("documentUrl") != null) {
                documentBytes = downloadFile((String) ticketInfo.get("documentUrl"));
            } else if (ticketInfo.get("documentBase64") != null) {
                documentBytes = java.util.Base64.getDecoder().decode((String) ticketInfo.get("documentBase64"));
            } else {
                throw new RuntimeException("工单没有文档");
            }

            // 3. 解析文档
            String documentType = documentParserService.detectFileType(documentBytes, "auto");
            String documentText = documentParserService.parseDocument(documentBytes, documentType);
            if (documentText == null) {
                throw new RuntimeException("无法解析文档内容");
            }

            // 4. 加载数据
            Map<String, Object> userData = new HashMap<>();
            if (ticketInfo.get("data") != null) {
                userData = objectMapper.convertValue(ticketInfo.get("data"), Map.class);
            }

            // 5. 加载规则
            List<RuleDto> ruleDtos = ruleGroupService.getRulesByGroupId(ruleGroupId);
            if (ruleDtos.isEmpty()) {
                throw new RuntimeException("规则组为空: " + ruleGroupId);
            }
            List<Rule> rules = convertDtosToRules(ruleDtos);

            // 6. 执行 AI 审核
            ApiConfig auditConfig = apiConfigService.getRawApiConfig();
            if (auditConfig == null) {
                auditConfig = ApiConfig.builder()
                        .endpoint("https://api.deepseek.com/v1/chat/completions")
                        .model("deepseek-chat")
                        .auditRole("专业文档审核专家")
                        .build();
            }

            List<AuditResultDto> results = aiAuditService.performAudit(
                    rules, documentText, auditConfig, userData, true, 0);

            // 7. 保存结果到 audit_feedback（异步来源标记为 async）
            List<com.smartdoc.entity.AuditFeedback> savedFeedbacks =
                    auditFeedbackService.saveAuditResults(results, ruleGroupId, null, "async");

            // 8. 保存 (ticketId, ts) 映射并标记 COMPLETED
            String batchNo = savedFeedbacks != null && !savedFeedbacks.isEmpty()
                    ? savedFeedbacks.get(0).getAuditBatchNo()
                    : UUID.randomUUID().toString().replace("-", "").substring(0, 16);

            AuditTicketRecord record = auditTicketRecordMapper.findByTaskId(taskId);
            if (record != null) {
                record.setAuditBatchNo(batchNo);
                record.setDocumentName(documentName);
                record.setStatus(AuditTicketRecord.STATUS_COMPLETED);
                auditTicketRecordMapper.updateById(record);
            }

            log.info("异步审核任务完成: taskId={}, batchNo={}", taskId, batchNo);

        } catch (Exception e) {
            log.error("异步审核任务失败: taskId={}, error={}", taskId, e.getMessage(), e);
            updateStatus(taskId, AuditTicketRecord.STATUS_FAILED, e.getMessage());
        }
    }

    private void updateStatus(String taskId, String status, String errorMessage) {
        AuditTicketRecord record = auditTicketRecordMapper.findByTaskId(taskId);
        if (record != null) {
            record.setStatus(status);
            record.setErrorMessage(errorMessage);
            auditTicketRecordMapper.updateById(record);
        }
    }

    private Map<String, Object> fetchTicketInfo(ApiConfig config, String ticketId) throws Exception {
        String url = config.getTicketEndpoint().replace("{id}", ticketId);
        if (!url.contains(ticketId)) {
            url = url.replaceAll("\\{ticketId}", ticketId);
        }

        RestTemplate rt = createRestTemplate(15000);
        HttpHeaders headers = new HttpHeaders();
        if (config.getTicketToken() != null && !config.getTicketToken().isEmpty()) {
            headers.set("Authorization", "Bearer " + config.getTicketToken());
        }
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = rt.exchange(url, HttpMethod.GET, entity, String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("工单系统返回异常: " + response.getStatusCode());
        }

        JsonNode root = objectMapper.readTree(response.getBody());
        Map<String, Object> result = new HashMap<>();
        if (root.has("documentUrl")) result.put("documentUrl", root.get("documentUrl").asText());
        if (root.has("documentBase64")) result.put("documentBase64", root.get("documentBase64").asText());
        if (root.has("documentName")) result.put("documentName", root.get("documentName").asText());
        if (root.has("data")) result.put("data", objectMapper.convertValue(root.get("data"), Map.class));

        return result;
    }

    private byte[] downloadFile(String url) {
        String lowerUrl = url.toLowerCase();
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
            throw new RuntimeException("不支持的URL协议");
        }
        RestTemplate rt = createRestTemplate(60000);
        ResponseEntity<byte[]> response = rt.getForEntity(url, byte[].class);
        return response.getBody();
    }

    private RestTemplate createRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
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
                .collect(java.util.stream.Collectors.toList());
    }
}
