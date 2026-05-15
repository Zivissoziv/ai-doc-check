package com.smartdoc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdoc.dto.AuditResultDto;
import com.smartdoc.dto.RuleFeedbackStatsDto;
import com.smartdoc.entity.AuditFeedback;
import com.smartdoc.entity.Rule;
import com.smartdoc.exception.BusinessException;
import com.smartdoc.mapper.AuditFeedbackMapper;
import com.smartdoc.mapper.RuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditFeedbackService {

    private final AuditFeedbackMapper auditFeedbackMapper;
    private final RuleMapper ruleMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public List<AuditFeedback> saveAuditResults(List<AuditResultDto> results) {
        return saveAuditResults(results, null);
    }

    @Transactional
    public List<AuditFeedback> saveAuditResults(List<AuditResultDto> results, String groupId) {
        List<AuditFeedback> feedbacks = new ArrayList<>();
        for (AuditResultDto result : results) {
            String resultsJson;
            try {
                resultsJson = objectMapper.writeValueAsString(result);
            } catch (JsonProcessingException e) {
                log.error("序列化审核结果失败: {}", e.getMessage(), e);
                throw new BusinessException("序列化审核结果失败: " + e.getMessage());
            }
            AuditFeedback feedback = AuditFeedback.builder()
                    .ruleId(result.getRuleId() != null ? result.getRuleId().longValue() : null)
                    .groupId(groupId)
                    .pass(result.getPass())
                    .confidence(result.getConfidence())
                    .resultsJson(resultsJson)
                    .build();
            feedbacks.add(feedback);
        }
        for (AuditFeedback feedback : feedbacks) {
            auditFeedbackMapper.insert(feedback);
        }
        return feedbacks;
    }

    @Transactional
    public AuditFeedback submitFeedback(Long feedbackId, String feedbackType, String reason) {
        AuditFeedback feedback = auditFeedbackMapper.selectById(feedbackId);
        if (feedback == null) {
            throw new BusinessException("反馈记录不存在");
        }
        if (!"ACCURATE".equals(feedbackType) && !"INACCURATE".equals(feedbackType)) {
            throw new BusinessException("反馈类型无效，必须是 ACCURATE 或 INACCURATE");
        }
        if ("INACCURATE".equals(feedbackType) && !StringUtils.hasText(reason)) {
            throw new BusinessException("标记为不准确时必须提供原因");
        }
        feedback.setFeedbackType(feedbackType);
        feedback.setReason(reason);
        auditFeedbackMapper.updateById(feedback);
        return feedback;
    }

    public RuleFeedbackStatsDto getRuleStats(Long ruleId) {
        AuditFeedbackMapper mapper = auditFeedbackMapper;

        Long totalAuditCount = Long.valueOf(mapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AuditFeedback>()
                        .eq(AuditFeedback::getRuleId, ruleId)));
        Long passCount = mapper.countByRuleIdAndPass(ruleId, true);

        double passRate = 0.0;
        if (totalAuditCount != null && totalAuditCount > 0) {
            passRate = (double) passCount / totalAuditCount * 100;
        }

        Long totalFeedbackCount = Long.valueOf(mapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AuditFeedback>()
                        .eq(AuditFeedback::getRuleId, ruleId)
                        .isNotNull(AuditFeedback::getFeedbackType)));
        Long accurateCount = mapper.countByRuleIdAndFeedbackType(ruleId, "ACCURATE");
        Long inaccurateCount = mapper.countByRuleIdAndFeedbackType(ruleId, "INACCURATE");

        List<AuditFeedback> recentFeedbacks = mapper.findRecentFeedbacks(ruleId, 5);
        List<RuleFeedbackStatsDto.FeedbackItem> feedbackItems = recentFeedbacks.stream()
                .map(f -> RuleFeedbackStatsDto.FeedbackItem.builder()
                        .feedbackType(f.getFeedbackType())
                        .reason(f.getReason())
                        .createdAt(f.getCreatedAt())
                        .build())
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
}
