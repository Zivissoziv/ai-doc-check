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

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("audit_ticket_record")
public class AuditTicketRecord {

    /** 任务状态：等待执行 */
    public static final String STATUS_PENDING = "PENDING";
    /** 任务状态：执行中 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 任务状态：已完成 */
    public static final String STATUS_COMPLETED = "COMPLETED";
    /** 任务状态：执行失败 */
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("ticket_id")
    private String ticketId;

    @TableField("ts")
    private String ts;

    @TableField("audit_batch_no")
    private String auditBatchNo;

    @TableField("document_name")
    private String documentName;

    @TableField("task_id")
    private String taskId;

    @TableField("status")
    private String status;

    @TableField("error_message")
    private String errorMessage;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
