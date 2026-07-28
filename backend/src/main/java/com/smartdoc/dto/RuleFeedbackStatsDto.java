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
public class RuleFeedbackStatsDto {

    private Long ruleId;

    private String ruleName;

    private Long totalAuditCount;

    private Long passCount;

    private Double passRate;

    private Long totalFeedbackCount;

    private Long accurateCount;

    private Long inaccurateCount;

    private List<FeedbackItem> recentFeedbacks;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FeedbackItem {

        private String feedbackType;

        private String reason;

        private String ticketId;

        private String ts;

        private String auditBatchNo;

        private LocalDateTime createdAt;
    }
}
