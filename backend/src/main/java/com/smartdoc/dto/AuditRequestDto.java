package com.smartdoc.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditRequestDto {

    private String ruleGroupId;

    private String documentUrl;

    private String documentBase64;

    @Builder.Default
    private String documentType = "auto";

    private String excelUrl;

    private String excelBase64;

    private Object data;

    private AuditSettingsDto settings;
}