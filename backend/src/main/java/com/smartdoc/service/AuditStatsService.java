package com.smartdoc.service;

import com.smartdoc.entity.AuditStats;
import com.smartdoc.mapper.AuditStatsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditStatsService {

    private final AuditStatsMapper auditStatsMapper;

    public AuditStats getStats() {
        AuditStats stats = auditStatsMapper.selectById(1);
        if (stats == null) {
            return AuditStats.builder()
                    .totalCount(0)
                    .todayCount(0)
                    .lastDate("")
                    .build();
        }

        String today = getTodayDate();
        if (!today.equals(stats.getLastDate())) {
            stats.setTodayCount(0);
            stats.setLastDate(today);
            auditStatsMapper.updateById(stats);
        }

        return stats;
    }

    @Transactional
    public AuditStats increment() {
        AuditStats stats = auditStatsMapper.selectById(1);
        String today = getTodayDate();

        if (stats == null) {
            stats = AuditStats.builder()
                    .id(1L)
                    .totalCount(1)
                    .todayCount(1)
                    .lastDate(today)
                    .build();
            auditStatsMapper.insert(stats);
        } else {
            if (!today.equals(stats.getLastDate())) {
                stats.setTodayCount(0);
                stats.setLastDate(today);
            }
            stats.setTotalCount(stats.getTotalCount() + 1);
            stats.setTodayCount(stats.getTodayCount() + 1);
            auditStatsMapper.updateById(stats);
        }

        return stats;
    }

    private String getTodayDate() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }
}