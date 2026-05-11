package com.smartdoc.service;

import com.smartdoc.entity.AuditDailyStats;
import com.smartdoc.entity.AuditFeedback;
import com.smartdoc.entity.AuditStats;
import com.smartdoc.mapper.AuditDailyStatsMapper;
import com.smartdoc.mapper.AuditFeedbackMapper;
import com.smartdoc.mapper.AuditStatsMapper;
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

    private final AuditStatsMapper auditStatsMapper;
    private final AuditDailyStatsMapper auditDailyStatsMapper;
    private final AuditFeedbackMapper auditFeedbackMapper;
    private final RuleMapper ruleMapper;

    public Map<String, Object> getStats() {
        AuditStats stats = auditStatsMapper.selectById(1);
        if (stats == null) {
            stats = AuditStats.builder().totalCount(0).todayCount(0).lastDate("").build();
        }
        String today = getTodayDate();
        if (!today.equals(stats.getLastDate())) {
            stats.setTodayCount(0);
            stats.setLastDate(today);
            auditStatsMapper.updateById(stats);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("totalCount", stats.getTotalCount());
        result.put("todayCount", stats.getTodayCount());
        return result;
    }

    @Transactional
    public AuditStats increment() {
        AuditStats stats = auditStatsMapper.selectById(1);
        String today = getTodayDate();

        if (stats == null) {
            stats = AuditStats.builder().id(1L).totalCount(1).todayCount(1).lastDate(today).build();
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

        upsertDailyStats(LocalDate.now(), 1, 0);

        return stats;
    }

    @Transactional
    public void recordAuditDuration(long durationMs, String groupId) {
        upsertDailyStats(LocalDate.now(), 0, durationMs);

        List<Long> ruleIds = ruleMapper.findIdsByGroupId(groupId);
        if (!ruleIds.isEmpty()) {
            List<AuditFeedback> feedbacks = auditFeedbackMapper.findLatestWithoutDuration(ruleIds, ruleIds.size());
            if (!feedbacks.isEmpty()) {
                long perRule = durationMs / feedbacks.size();
                for (AuditFeedback f : feedbacks) {
                    f.setDurationMs(perRule);
                    auditFeedbackMapper.updateById(f);
                }
            }
        }
    }

    private void upsertDailyStats(LocalDate date, int countIncr, long durationMs) {
        AuditDailyStats daily = auditDailyStatsMapper.findByDate(date);
        if (daily == null) {
            daily = AuditDailyStats.builder()
                    .statDate(date)
                    .count(countIncr)
                    .totalDurationMs(durationMs)
                    .build();
            auditDailyStatsMapper.insert(daily);
        } else {
            daily.setCount(daily.getCount() + countIncr);
            daily.setTotalDurationMs((daily.getTotalDurationMs() != null ? daily.getTotalDurationMs() : 0) + durationMs);
            auditDailyStatsMapper.updateById(daily);
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getDailyStats() {
        LocalDate sevenDaysAgo = LocalDate.now().minusDays(6);
        List<AuditDailyStats> dailyList = auditDailyStatsMapper.findSince(sevenDaysAgo);

        Map<LocalDate, AuditDailyStats> map = dailyList.stream()
                .collect(Collectors.toMap(AuditDailyStats::getStatDate, d -> d));

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate date = LocalDate.now().minusDays(i);
            AuditDailyStats ds = map.get(date);
            Map<String, Object> item = new HashMap<>();
            item.put("date", date.format(DateTimeFormatter.ofPattern("MM-dd")));
            item.put("count", ds != null ? ds.getCount() : 0);
            item.put("avgDurationMs", (ds != null && ds.getCount() > 0)
                    ? ds.getTotalDurationMs() / ds.getCount() : 0);
            result.add(item);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getGroupStats(String groupId) {
        Map<String, Object> result = new HashMap<>();
        result.put("groupId", groupId);

        List<Long> ruleIds = ruleMapper.findIdsByGroupId(groupId);
        if (ruleIds.isEmpty()) {
            result.put("totalAuditCount", 0);
            result.put("passRate", 0);
            result.put("avgDurationMs", 0);
            return result;
        }

        List<AuditFeedback> feedbacks = auditFeedbackMapper.findByRuleIds(ruleIds);
        long totalCount = feedbacks.size();
        long passCount = feedbacks.stream().filter(f -> Boolean.TRUE.equals(f.getPass())).count();
        long totalDuration = feedbacks.stream()
                .mapToLong(f -> f.getDurationMs() != null ? f.getDurationMs() : 0)
                .sum();

        result.put("totalAuditCount", totalCount);
        result.put("passRate", totalCount > 0 ? Math.round((double) passCount / totalCount * 100) : 0);

        long auditRuns = ruleIds.size() > 0 ? totalCount / ruleIds.size() : 0;
        result.put("avgDurationMs", auditRuns > 0 ? totalDuration / auditRuns : 0);

        return result;
    }

    private String getTodayDate() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }
}
