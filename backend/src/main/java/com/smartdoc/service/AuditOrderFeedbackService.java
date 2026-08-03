package com.smartdoc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdoc.dto.AuditResultDto;
import com.smartdoc.dto.RuleFeedbackStatsDto;
import com.smartdoc.entity.AuditOrderFeedback;
import com.smartdoc.entity.AuditOrderRecord;
import com.smartdoc.entity.Rule;
import com.smartdoc.exception.BusinessException;
import com.smartdoc.mapper.AuditOrderFeedbackMapper;
import com.smartdoc.mapper.AuditOrderRecordMapper;
import com.smartdoc.mapper.RuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditOrderFeedbackService {

    private final AuditOrderFeedbackMapper auditOrderFeedbackMapper;
    private final AuditOrderRecordMapper auditOrderRecordMapper;
    private final RuleMapper ruleMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public List<AuditOrderFeedback> saveAuditResults(List<AuditResultDto> results, String groupId, Long durationMs) {
        return saveAuditResults(results, groupId, durationMs, null);
    }

    @Transactional
    public List<AuditOrderFeedback> saveAuditResults(List<AuditResultDto> results, String groupId, Long durationMs, String source) {
        List<AuditOrderFeedback> feedbacks = new ArrayList<>();
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String batchNo = "async".equals(source) ? uuid + "_async" : uuid;

        for (AuditResultDto result : results) {
            String resultsJson;
            try {
                resultsJson = objectMapper.writeValueAsString(result);
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize order audit result: {}", e.getMessage(), e);
                throw new BusinessException("Failed to serialize order audit result: " + e.getMessage());
            }

            AuditOrderFeedback feedback = AuditOrderFeedback.builder()
                    .ruleId(result.getRuleId() != null ? result.getRuleId().longValue() : null)
                    .groupId(groupId)
                    .auditBatchNo(batchNo)
                    .durationMs(durationMs)
                    .pass(result.getPass())
                    .skipped(result.getSkipped())
                    .confidence(result.getConfidence())
                    .resultsJson(resultsJson)
                    .build();
            feedbacks.add(feedback);
        }

        for (AuditOrderFeedback feedback : feedbacks) {
            auditOrderFeedbackMapper.insert(feedback);
        }
        return feedbacks;
    }

    @Transactional
    public AuditOrderFeedback submitFeedback(Long feedbackId, String feedbackType, String reason) {
        AuditOrderFeedback feedback = auditOrderFeedbackMapper.selectById(feedbackId);
        if (feedback == null) {
            throw new BusinessException("Feedback record does not exist");
        }
        if (!"ACCURATE".equals(feedbackType) && !"INACCURATE".equals(feedbackType)) {
            throw new BusinessException("Invalid feedback type, must be ACCURATE or INACCURATE");
        }
        if ("INACCURATE".equals(feedbackType) && !StringUtils.hasText(reason)) {
            throw new BusinessException("Reason is required when feedback is INACCURATE");
        }
        feedback.setFeedbackType(feedbackType);
        feedback.setReason(reason);
        auditOrderFeedbackMapper.updateById(feedback);
        return feedback;
    }

    public RuleFeedbackStatsDto getRuleStats(Long ruleId, String startDate, String endDate) {
        Long totalAuditCount;
        Long passCount;
        boolean hasDate = startDate != null || endDate != null;
        if (hasDate) {
            totalAuditCount = auditOrderFeedbackMapper.countByRuleIdNonSkipped(ruleId, startDate, endDate);
            passCount = auditOrderFeedbackMapper.countByRuleIdPass(ruleId, startDate, endDate);
        } else {
            totalAuditCount = Long.valueOf(auditOrderFeedbackMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AuditOrderFeedback>()
                            .eq(AuditOrderFeedback::getRuleId, ruleId)
                            .ne(AuditOrderFeedback::getSkipped, true)));
            passCount = auditOrderFeedbackMapper.countByRuleIdAndPass(ruleId, true);
        }

        double passRate = 0.0;
        if (totalAuditCount != null && totalAuditCount > 0) {
            passRate = (double) passCount / totalAuditCount * 100;
        }

        Long totalFeedbackCount = Long.valueOf(auditOrderFeedbackMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AuditOrderFeedback>()
                        .eq(AuditOrderFeedback::getRuleId, ruleId)
                        .isNotNull(AuditOrderFeedback::getFeedbackType)));
        Long accurateCount = auditOrderFeedbackMapper.countByRuleIdAndFeedbackType(ruleId, "ACCURATE");
        Long inaccurateCount = auditOrderFeedbackMapper.countByRuleIdAndFeedbackType(ruleId, "INACCURATE");

        List<AuditOrderFeedback> recentFeedbacks = auditOrderFeedbackMapper.findRecentFeedbacks(ruleId, 5);
        Map<String, AuditOrderRecord> orderRecordByBatchNo = findOrderRecordsByBatchNo(recentFeedbacks);
        List<RuleFeedbackStatsDto.FeedbackItem> feedbackItems = recentFeedbacks.stream()
                .map(f -> {
                    AuditOrderRecord orderRecord = orderRecordByBatchNo.get(f.getAuditBatchNo());
                    return RuleFeedbackStatsDto.FeedbackItem.builder()
                            .feedbackType(f.getFeedbackType())
                            .reason(f.getReason())
                            .auditBatchNo(f.getAuditBatchNo())
                            .orderId(orderRecord != null ? orderRecord.getOrderId() : null)
                            .ts(orderRecord != null ? orderRecord.getTs() : null)
                            .createdAt(f.getCreatedAt())
                            .build();
                })
                .collect(Collectors.toList());

        String ruleName = null;
        Rule rule = ruleMapper.selectById(ruleId);
        if (rule != null) {
            ruleName = rule.getRuleName();
        }

        return RuleFeedbackStatsDto.builder()
                .ruleId(ruleId)
                .ruleName(ruleName)
                .totalAuditCount(totalAuditCount)
                .passCount(passCount)
                .passRate(passRate)
                .totalFeedbackCount(totalFeedbackCount)
                .accurateCount(accurateCount)
                .inaccurateCount(inaccurateCount)
                .recentFeedbacks(feedbackItems)
                .build();
    }

    public List<AuditOrderFeedback> getRuleFailures(Long ruleId, String startDate, String endDate, int limit) {
        return auditOrderFeedbackMapper.findFailuresByRuleId(ruleId, startDate, endDate, limit);
    }

    public Map<String, AuditOrderRecord> findOrderRecordsByBatchNo(List<AuditOrderFeedback> feedbacks) {
        List<String> batchNos = feedbacks.stream()
                .map(AuditOrderFeedback::getAuditBatchNo)
                .filter(batchNo -> batchNo != null && !batchNo.isEmpty())
                .distinct()
                .collect(Collectors.toList());

        Map<String, AuditOrderRecord> result = new HashMap<>();
        if (batchNos.isEmpty()) {
            return result;
        }

        for (AuditOrderRecord record : auditOrderRecordMapper.findByBatchNos(batchNos)) {
            if (record.getAuditBatchNo() != null && !result.containsKey(record.getAuditBatchNo())) {
                result.put(record.getAuditBatchNo(), record);
            }
        }
        return result;
    }
}
