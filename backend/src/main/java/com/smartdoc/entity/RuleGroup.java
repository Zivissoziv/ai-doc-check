package com.smartdoc.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

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

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(exist = false)
    private List<Rule> rules;
}