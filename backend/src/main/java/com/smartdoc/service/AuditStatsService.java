package com.smartdoc.service;

import com.smartdoc.entity.AuditFeedback;
import com.smartdoc.mapper.AuditFeedbackMapper;
import com.smartdoc.mapper.RuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditStatsService {

    private final AuditFeedbackMapper auditFeedbackMapper;
    private final RuleMapper ruleMapper;

    public Map<String, Object> getStats() {
        return getStats(null, null);
    }

    public Map<String, Object> getStats(String startDate, String endDate) {
        Map<String, Object> result = new HashMap<>();

        if (hasDateRange(startDate, endDate)) {
            long totalCount = auditFeedbackMapper.countBatchesBetween(startDate, endDate);
            LocalDate today = LocalDate.now();
            String todayStr = today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            long todayCount = 0;
            if (todayStr.compareTo(startDate) >= 0 && todayStr.compareTo(endDate) <= 0) {
                todayCount = auditFeedbackMapper.countTodayBatches();
            }
            result.put("totalCount", totalCount);
            result.put("todayCount", todayCount);
        } else {
            long totalCount = auditFeedbackMapper.countTotalBatches();
            long todayCount = auditFeedbackMapper.countTodayBatches();
            result.put("totalCount", totalCount);
            result.put("todayCount", todayCount);
        }
        return result;
    }

    @Transactional
    public void increment() {
        // 已废弃：统计全部基于 audit_feedback 的 audit_batch_no
        // 保留空方法兼容前端遗留调用
    }

    @Transactional
    public void recordAuditDuration(long durationMs, String groupId) {
        // 已废弃：统计全部基于 audit_feedback 的 audit_batch_no
        // 保留空方法兼容前端遗留调用
    }

    public List<Map<String, Object>> getDailyStats() {
        return getDailyStats(null, null);
    }

    public List<Map<String, Object>> getDailyStats(String startDate, String endDate) {
        List<AuditFeedbackMapper.DailyStatsRow> rows;

        if (hasDateRange(startDate, endDate)) {
            rows = auditFeedbackMapper.findDailyStatsBetween(startDate, endDate);
        } else {
            String sevenDaysAgo = LocalDate.now().minusDays(6).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            rows = auditFeedbackMapper.findDailyStatsSince(sevenDaysAgo);
        }

        Map<String, AuditFeedbackMapper.DailyStatsRow> map = rows.stream()
                .collect(Collectors.toMap(AuditFeedbackMapper.DailyStatsRow::getStatDate, r -> r));

        List<Map<String, Object>> result = new ArrayList<>();
        if (hasDateRange(startDate, endDate)) {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                result.add(buildDailyItem(date, map.get(date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))));
            }
        } else {
            for (int i = 6; i >= 0; i--) {
                LocalDate date = LocalDate.now().minusDays(i);
                result.add(buildDailyItem(date, map.get(date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))));
            }
        }
        return result;
    }

    private Map<String, Object> buildDailyItem(LocalDate date, AuditFeedbackMapper.DailyStatsRow row) {
        Map<String, Object> item = new HashMap<>();
        item.put("date", date.format(DateTimeFormatter.ofPattern("MM-dd")));
        item.put("count", row != null ? row.getCount() : 0L);
        item.put("avgDurationMs", row != null ? row.getAvgDurationMs() : 0L);
        return item;
    }

    public Map<String, Object> getGroupStats(String groupId) {
        return getGroupStats(groupId, null, null);
    }

    public Map<String, Object> getGroupStats(String groupId, String startDate, String endDate) {
        Map<String, Object> result = new HashMap<>();
        result.put("groupId", groupId);

        List<AuditFeedback> feedbacks;

        if (hasDateRange(startDate, endDate)) {
            feedbacks = auditFeedbackMapper.findByGroupIdBetween(groupId, startDate, endDate + " 23:59:59");
        } else {
            List<AuditFeedback> groupFeedbacks = auditFeedbackMapper.findByGroupId(groupId);
            if (!groupFeedbacks.isEmpty()) {
                feedbacks = groupFeedbacks;
            } else {
                List<Long> ruleIds = ruleMapper.findIdsByGroupId(groupId);
                if (ruleIds.isEmpty()) {
                    result.put("totalAuditCount", 0);
                    result.put("failCount", 0);
                    result.put("avgDurationMs", 0);
                    return result;
                }
                feedbacks = auditFeedbackMapper.findByRuleIds(ruleIds);
            }
        }

        List<AuditFeedback> nonSkipped = feedbacks.stream()
                .filter(f -> !Boolean.TRUE.equals(f.getSkipped()))
                .collect(Collectors.toList());

        long totalCount = nonSkipped.size();
        long failCount = nonSkipped.stream()
                .filter(f -> !Boolean.TRUE.equals(f.getPass()))
                .count();

        // 按批次去重计算总耗时（同批次多条记录 duration_ms 相同，避免重复累加）
        long totalDuration = nonSkipped.stream()
                .filter(f -> f.getAuditBatchNo() != null && f.getDurationMs() != null)
                .collect(Collectors.groupingBy(
                        AuditFeedback::getAuditBatchNo,
                        Collectors.reducing(0L, AuditFeedback::getDurationMs, Long::max)
                ))
                .values().stream().mapToLong(Long::longValue).sum();

        // 补充没有批次号的旧数据的耗时（直接累加）
        totalDuration += nonSkipped.stream()
                .filter(f -> f.getAuditBatchNo() == null && f.getDurationMs() != null)
                .mapToLong(AuditFeedback::getDurationMs)
                .sum();

        result.put("totalAuditCount", totalCount);
        result.put("failCount", failCount);

        // 通过去重批次号计算实际审核轮次
        long auditRuns = nonSkipped.stream()
                .map(AuditFeedback::getAuditBatchNo)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();

        if (auditRuns == 0) {
            long ruleCount = ruleMapper.findIdsByGroupId(groupId).size();
            auditRuns = ruleCount > 0 ? totalCount / ruleCount : 0;
        }

        result.put("avgDurationMs", auditRuns > 0 ? totalDuration / auditRuns : 0);

        return result;
    }

    private boolean hasDateRange(String startDate, String endDate) {
        return startDate != null && !startDate.isEmpty()
                && endDate != null && !endDate.isEmpty();
    }
}
