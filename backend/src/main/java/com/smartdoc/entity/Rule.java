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

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("rule")
public class Rule {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("rule_group_id")
    private Long ruleGroupId;

    @NotBlank
    @Size(max = 200)
    @TableField("rule_name")
    private String ruleName;

    @NotBlank
    @TableField("prompt")
    private String prompt;

    @TableField("severity")
    @Builder.Default
    private Severity severity = Severity.WARNING;

    @TableField("is_enabled")
    @Builder.Default
    private Boolean isEnabled = true;

    @TableField("sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public enum Severity {
        /** 错误（必须修复） */
        ERROR,
        /** 警告（建议修改） */
        WARNING,
        /** 提示（仅供参考） */
        INFO
    }
}
