package com.smartdoc.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditResultDto {

    private Integer ruleId;

    private String ruleName;

    private String severity;

    private Boolean pass;

    private Boolean skipped;

    private Integer confidence;

    private List<AuditIssueDto> issues;

    private String summary;

    private Long _feedbackId;

    private String _feedbackType;
}