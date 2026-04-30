package com.smartdoc.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditFeedbackDto {

    private Long feedbackId;

    private String feedbackType;

    private String reason;
}
