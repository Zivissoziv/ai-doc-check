package com.smartdoc.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditSettingsDto {

    private String endpoint;

    private String apiKey;

    private String model;

    private String auditRole;

    @Builder.Default
    private Boolean repeatPrompt = true;

    @Builder.Default
    private Integer batchSize = 0;

    @Builder.Default
    private Double temperature = 0.1;

    @Builder.Default
    private Double topP = 1.0;
}