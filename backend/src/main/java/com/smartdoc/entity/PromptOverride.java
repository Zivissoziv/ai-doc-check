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
 * 提示词自定义覆盖：按模板名（prompt_key，对应 prompts/{key}.prompt）保存用户改过的提示词。
 * 表中没有记录时一律使用 classpath 下的内置模板。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("prompt_override")
public class PromptOverride {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 提示词模板名，如 rule-training-brief-user
     */
    @TableField("prompt_key")
    private String promptKey;

    /**
     * 用户自定义内容（含 {id} 形式占位符，替换逻辑与内置模板一致）
     */
    @TableField("content")
    private String content;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
