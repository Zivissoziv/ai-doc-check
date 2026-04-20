package com.smartdoc.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("audit_stats")
public class AuditStats {
    private Long id;
    private Integer totalCount;
    private Integer todayCount;
    private String lastDate;
}