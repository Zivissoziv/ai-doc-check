package com.smartdoc.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditOrderRecordDto {

    private Boolean exists;

    private String orderId;

    private String ts;

    private String auditBatchNo;

    private String documentName;

    private List<AuditResultDto> results;

    private LocalDateTime auditedAt;

    private Integer totalCount;

    private Integer passCount;

    private Integer skippedCount;

    private Integer failCount;

    private List<FailureItem> failures;

    private String taskStatus;

    private String errorMessage;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FailureItem {
        private String ruleName;
        private String summary;
        private List<AuditIssueDto> issues;
    }
}
