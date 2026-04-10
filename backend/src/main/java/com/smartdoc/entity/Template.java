package com.smartdoc.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("template")
public class Template {

    @TableId(type = IdType.AUTO)
    private Long id;

    @NotBlank
    @Size(max = 200)
    @TableField("template_name")
    private String templateName;

    @Size(max = 100)
    @TableField("file_name")
    private String fileName;

    @TableField("description")
    private String description;

    @TableField("is_default")
    @Builder.Default
    private Boolean isDefault = false;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}