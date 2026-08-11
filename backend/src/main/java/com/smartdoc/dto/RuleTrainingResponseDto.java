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
public class RuleTrainingResponseDto {

    private List<RuleTrainingCandidateDto> rules;

    private List<String> duplicateHints;
}
