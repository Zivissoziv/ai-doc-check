package com.smartdoc.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleTrainingCandidateDto {

    private String name;

    private String riskType;

    private String sourceInsight;

    private String generalizedRisk;

    private String triggerScenario;

    private String prompt;

    private String severity;

    private String passExample;

    private String failExample;

    private String auditScope;
}
