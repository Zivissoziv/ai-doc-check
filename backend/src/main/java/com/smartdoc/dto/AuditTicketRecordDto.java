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
public class AuditTicketRecordDto {

    private Boolean exists;

    private String auditBatchNo;

    private String documentName;

    private List<AuditResultDto> results;

    private LocalDateTime auditedAt;

    /** 审核总条数 */
    private Integer totalCount;

    /** 通过条数 */
    private Integer passCount;

    /** 跳过条数 */
    private Integer skippedCount;

    /** 不通过条数 */
    private Integer failCount;

    /** 不通过的具体原因列表 */
    private List<FailureItem> failures;

    /** 异步任务状态：PENDING / RUNNING / COMPLETED / FAILED */
    private String taskStatus;

    /** 任务失败时的错误信息 */
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
