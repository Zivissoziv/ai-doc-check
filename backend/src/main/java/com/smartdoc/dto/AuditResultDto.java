package com.smartdoc.dto;

import lombok.*;

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

    private Integer confidence;

    private List<AuditIssueDto> issues;

    private String summary;
}