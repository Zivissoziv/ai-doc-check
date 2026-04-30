package com.smartdoc.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("audit_feedback")
public class AuditFeedback {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("rule_id")
    private Long ruleId;

    @TableField("pass")
    private Boolean pass;

    @TableField("confidence")
    private Integer confidence;

    @TableField("results_json")
    private String resultsJson;

    @TableField("feedback_type")
    private String feedbackType;

    @TableField("reason")
    private String reason;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public enum FeedbackType {
        ACCURATE, INACCURATE
    }
}
