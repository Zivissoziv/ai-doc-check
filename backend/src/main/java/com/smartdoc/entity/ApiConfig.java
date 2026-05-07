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

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }
}