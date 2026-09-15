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
public class PermissionGroupDto {

    private Long id;

    /**
     * URL 参数值（?pgroup=xxx）
     */
    @NotBlank(message = "权限组标识不能为空")
    @Size(max = 64, message = "权限组标识长度不能超过64")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "权限组标识只能包含字母、数字、下划线和横线")
    private String permKey;

    @NotBlank(message = "权限组名称不能为空")
    @Size(max = 100, message = "权限组名称长度不能超过100")
    private String permName;

    /**
     * 是否可见「变更简报」；null 表示不修改（更新时）
     */
    private Boolean briefVisible;

    /**
     * 可见规则组ID列表；null 或空表示全部规则组可见
     */
    private List<String> visibleGroupIds;
}
