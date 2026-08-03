package com.smartdoc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdoc.dto.AuditOrderRecordDto;
import com.smartdoc.dto.AuditResultDto;
import com.smartdoc.entity.AuditOrderFeedback;
import com.smartdoc.entity.AuditOrderRecord;
import com.smartdoc.mapper.AuditOrderFeedbackMapper;
import com.smartdoc.mapper.AuditOrderRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditOrderRecordService {

    private final AuditOrderRecordMapper auditOrderRecordMapper;
    private final AuditOrderFeedbackMapper auditOrderFeedbackMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public void saveRecord(String orderId, String ts, String auditBatchNo, String documentName) {
        AuditOrderRecord record = AuditOrderRecord.builder()
                .orderId(orderId)
                .ts(ts)
                .auditBatchNo(auditBatchNo)
                .documentName(documentName)
                .status(AuditOrderRecord.STATUS_COMPLETED)
                .build();
        auditOrderRecordMapper.insert(record);
        log.info("Created audit order detail: orderId={}, ts={}, batchNo={}", orderId, ts, auditBatchNo);
    }

    public AuditOrderRecordDto getAuditResultsByOrderIdAndTs(String orderId, String ts, Boolean summaryOnly) {
        AuditOrderRecord record = auditOrderRecordMapper.findByOrderIdAndTs(orderId, ts);
        if (record == null) {
            AuditOrderRecordDto dto = new AuditOrderRecordDto();
            dto.setExists(false);
            dto.setOrderId(orderId);
            dto.setTs(ts);
            return dto;
        }

        List<AuditOrderFeedback> feedbacks = new ArrayList<>();
        if (record.getAuditBatchNo() != null && !record.getAuditBatchNo().isEmpty()) {
            feedbacks = auditOrderFeedbackMapper.selectList(
                    new LambdaQueryWrapper<AuditOrderFeedback>()
                            .eq(AuditOrderFeedback::getAuditBatchNo, record.getAuditBatchNo())
            );
        }

        int totalCount = 0, passCount = 0, skippedCount = 0, failCount = 0;
        List<AuditOrderRecordDto.FailureItem> failures = new ArrayList<>();
        List<AuditResultDto> results = new ArrayList<>();

        for (AuditOrderFeedback feedback : feedbacks) {
            totalCount++;
            if (feedback.getSkipped() != null && feedback.getSkipped()) {
                skippedCount++;
            } else if (feedback.getPass() != null && feedback.getPass()) {
                passCount++;
            } else {
                failCount++;
            }

            try {
                if (feedback.getResultsJson() != null) {
                    AuditResultDto result = objectMapper.readValue(feedback.getResultsJson(), AuditResultDto.class);
                    result.setRuleId(feedback.getRuleId() != null ? feedback.getRuleId().intValue() : null);
                    result.setPass(feedback.getPass());
                    result.setSkipped(feedback.getSkipped());
                    if (feedback.getConfidence() != null) {
                        result.setConfidence(feedback.getConfidence());
                    }
                    result.set_feedbackId(feedback.getId());
                    result.set_feedbackType(feedback.getFeedbackType());

                    boolean isFail = (feedback.getSkipped() == null || !feedback.getSkipped())
                            && (feedback.getPass() == null || !feedback.getPass());
                    if (isFail) {
                        AuditOrderRecordDto.FailureItem failure = new AuditOrderRecordDto.FailureItem();
                        failure.setRuleName(result.getRuleName());
                        failure.setSummary(result.getSummary());
                        failure.setIssues(result.getIssues());
                        failures.add(failure);
                    }

                    if (!Boolean.TRUE.equals(summaryOnly)) {
                        results.add(result);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to deserialize order audit result: {}", e.getMessage(), e);
            }
        }

        AuditOrderRecordDto dto = new AuditOrderRecordDto();
        dto.setExists(true);
        dto.setOrderId(record.getOrderId());
        dto.setTs(record.getTs());
        dto.setAuditBatchNo(record.getAuditBatchNo());
        dto.setDocumentName(record.getDocumentName());
        dto.setAuditedAt(record.getCreatedAt());
        dto.setTotalCount(totalCount);
        dto.setPassCount(passCount);
        dto.setSkippedCount(skippedCount);
        dto.setFailCount(failCount);
        dto.setFailures(failures);
        dto.setTaskStatus(record.getStatus());
        dto.setErrorMessage(record.getErrorMessage());
        if (!Boolean.TRUE.equals(summaryOnly)) {
            dto.setResults(results);
        }
        return dto;
    }
}
