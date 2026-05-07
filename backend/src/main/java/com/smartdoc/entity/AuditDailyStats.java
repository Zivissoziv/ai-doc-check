package com.smartdoc.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("audit_daily_stats")
public class AuditDailyStats {
    private Long id;
    private LocalDate statDate;
    private Integer count;
    private Long totalDurationMs;
}
