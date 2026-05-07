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
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("rule_group")
public class RuleGroup {

    @TableId(type = IdType.AUTO)
    private Long id;

    @NotBlank
    @Size(max = 50)
    @TableField("group_id")
    private String groupId;

    @NotBlank
    @Size(max = 200)
    @TableField("group_name")
    private String groupName;

    @TableField("is_default")
    @Builder.Default
    private Boolean isDefault = false;

    @TableField("is_locked")
    @Builder.Default
    private Boolean isLocked = false;

    @TableField("lock_password")
    private String lockPassword;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(exist = false)
    private List<Rule> rules;
}