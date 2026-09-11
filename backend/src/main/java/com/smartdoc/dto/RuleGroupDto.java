package com.smartdoc.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    /**
     * 规则组类型：audit=审核规则组 / brief=变更简报总结规则组（默认 audit，兼容旧请求）
     */
    @Builder.Default
    private String groupType = "audit";

    /**
     * 简报风格（仅 brief 类型规则组使用，自由文本）
     */
    private String briefStyle;

    @Builder.Default
    private Boolean isDefault = false;

    @Builder.Default
    private Boolean locked = false;

    private List<RuleDto> rules;
}