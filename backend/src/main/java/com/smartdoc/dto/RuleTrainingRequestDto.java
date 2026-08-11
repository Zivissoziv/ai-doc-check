package com.smartdoc.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class RuleTrainingRequestDto {

    @NotBlank(message = "审核报告内容不能为空")
    @Size(max = 20000, message = "审核报告内容不能超过20000字")
    private String reviewReport;

    private String groupId;

    private String auditMode = "document";
}
