package com.smartdoc.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleDto {

    private Long id;

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    private String prompt;

    @Builder.Default
    private String severity = "warning";

    @Builder.Default
    private Boolean enabled = true;

    @Builder.Default
    private Integer sortOrder = 0;
}