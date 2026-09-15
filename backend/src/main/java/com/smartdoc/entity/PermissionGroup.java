package com.smartdoc.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 权限组：通过 URL 参数 ?pgroup=xxx 切换生效的页面可见性配置
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("permission_group")
public class PermissionGroup {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * URL 参数值（?pgroup=xxx），全局唯一
     */
    @TableField("perm_key")
    private String permKey;

    /**
     * 权限组名称（仅供管理端识别）
     */
    @TableField("perm_name")
    private String permName;

    /**
     * 是否可见「变更简报」入口
     */
    @TableField("brief_visible")
    @Builder.Default
    private Boolean briefVisible = false;

    /**
     * 可见规则组ID列表（JSON 数组字符串）；NULL 或空表示全部可见
     */
    @TableField("visible_group_ids")
    private String visibleGroupIds;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
