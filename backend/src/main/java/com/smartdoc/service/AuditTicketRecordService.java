package com.smartdoc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdoc.dto.AuditResultDto;
import com.smartdoc.dto.AuditTicketRecordDto;
import com.smartdoc.entity.AuditFeedback;
import com.smartdoc.entity.AuditTicketRecord;
import com.smartdoc.mapper.AuditFeedbackMapper;
import com.smartdoc.mapper.AuditTicketRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditTicketRecordService {

    private final AuditTicketRecordMapper auditTicketRecordMapper;
    private final AuditFeedbackMapper auditFeedbackMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public void saveRecord(String ticketId, String ts, String auditBatchNo, String documentName) {
        AuditTicketRecord record = AuditTicketRecord.builder()
                .ticketId(ticketId)
                .ts(ts)
                .auditBatchNo(auditBatchNo)
                .documentName(documentName)
                .status(AuditTicketRecord.STATUS_COMPLETED)
                .build();
        auditTicketRecordMapper.insert(record);
        log.info("Created audit ticket detail: ticketId={}, ts={}, batchNo={}", ticketId, ts, auditBatchNo);
    }

    public AuditTicketRecord getByTicketIdAndTs(String ticketId, String ts) {
        return auditTicketRecordMapper.findByTicketIdAndTs(ticketId, ts);
    }

    public AuditTicketRecordDto getAuditResultsByTicketIdAndTs(String ticketId, String ts) {
        return getAuditResultsByTicketIdAndTs(ticketId, ts, false);
    }

    public AuditTicketRecordDto getAuditResultsByTicketIdAndTs(String ticketId, String ts,
                                                                Boolean summaryOnly) {
        AuditTicketRecord record = auditTicketRecordMapper.findByTicketIdAndTs(ticketId, ts);
        if (record == null) {
            AuditTicketRecordDto dto = new AuditTicketRecordDto();
            dto.setExists(false);
            return dto;
        }

        List<AuditFeedback> feedbacks = new ArrayList<>();
        if (record.getAuditBatchNo() != null && !record.getAuditBatchNo().isEmpty()) {
            feedbacks = auditFeedbackMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AuditFeedback>()
                            .eq(AuditFeedback::getAuditBatchNo, record.getAuditBatchNo())
            );
        }

        int totalCount = 0, passCount = 0, skippedCount = 0, failCount = 0;
        List<AuditTicketRecordDto.FailureItem> failures = new ArrayList<>();
        List<AuditResultDto> results = new ArrayList<>();

        for (AuditFeedback feedback : feedbacks) {
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
                        AuditTicketRecordDto.FailureItem failure = new AuditTicketRecordDto.FailureItem();
                        failure.setRuleName(result.getRuleName());
                        failure.setSummary(result.getSummary());
                        failure.setIssues(result.getIssues());
                        failures.add(failure);
                    }

                    if (!summaryOnly) {
                        results.add(result);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to deserialize audit result: {}", e.getMessage(), e);
            }
        }

        AuditTicketRecordDto dto = new AuditTicketRecordDto();
        dto.setExists(true);
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
        if (!summaryOnly) {
            dto.setResults(results);
        }
        return dto;
    }
}
