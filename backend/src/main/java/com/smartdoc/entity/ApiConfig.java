package com.smartdoc.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.Size;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("api_config")
public class ApiConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    @Size(max = 50)
    @TableField("provider")
    @Builder.Default
    private String provider = "custom";

    @Size(max = 500)
    @TableField("endpoint")
    @Builder.Default
    private String endpoint = "https://api.deepseek.com/v1/chat/completions";

    @TableField("api_key")
    private String apiKey;

    @Size(max = 100)
    @TableField("model")
    @Builder.Default
    private String model = "deepseek-chat";

    @Size(max = 200)
    @TableField("audit_role")
    @Builder.Default
    private String auditRole = "专业文档审核专家";

    @Size(max = 500)
    @TableField("ticket_endpoint")
    private String ticketEndpoint;

    @TableField("ticket_token")
    private String ticketToken;

    @Size(max = 500)
    @TableField("order_audit_endpoint")
    private String orderAuditEndpoint;

    /**
     * 工单接口请求方法：GET / POST
     */
    @TableField("order_http_method")
    private String orderHttpMethod = "GET";

    /**
     * 列表请求体模板（POST 用），支持 {startTime}/{start}/{endTime}/{end} 占位符。
     * updateStrategy=IGNORED：允许把模板清空（MyBatis-Plus 默认忽略 null 字段，会导致"清空"变成"不改动"）
     */
    @TableField(value = "order_list_body", updateStrategy = FieldStrategy.IGNORED)
    private String orderListBody;

    /**
     * 单条工单请求体模板（POST 用），支持 {id}/{orderId} 占位符；为空则复用列表模板
     */
    @TableField(value = "order_detail_body", updateStrategy = FieldStrategy.IGNORED)
    private String orderDetailBody;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }
}
