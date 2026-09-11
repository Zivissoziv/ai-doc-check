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

    /**
     * 所属规则组ID（默认组+选中组多次审核时用于区分结果归属）
     */
    private String groupId;

    /**
     * 所属规则组名称（前端展示用标签）
     */
    private String groupName;

    private Long _feedbackId;

    private String _feedbackType;
}