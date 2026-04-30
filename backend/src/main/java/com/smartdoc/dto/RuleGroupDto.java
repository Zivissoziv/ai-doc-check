package com.smartdoc.dto;

import lombok.*;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleGroupDto {

    private Long id;

    @NotBlank
    @Size(max = 50)
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$")
    private String groupId;

    @NotBlank
    @Size(max = 100)
    private String name;

    @Builder.Default
    private Boolean isDefault = false;

    @Builder.Default
    private Boolean locked = false;

    private List<RuleDto> rules;
}