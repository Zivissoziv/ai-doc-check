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
        return getStats(null, null);
    }

    public Map<String, Object> getStats(String startDate, String endDate) {
        Map<String, Object> result = new HashMap<>();

        if (hasDateRange(startDate, endDate)) {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            long totalCount = auditDailyStatsMapper.sumCountBetween(start, end);
            long todayCount = 0;
            LocalDate today = LocalDate.now();
            if (!today.isBefore(start) && !today.isAfter(end)) {
                todayCount = auditDailyStatsMapper.sumCountByDate(today);
            }
            result.put("totalCount", totalCount);
            result.put("todayCount", todayCount);
        } else {
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
            result.put("totalCount", stats.getTotalCount());
            result.put("todayCount", stats.getTodayCount());
        }
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

        if (groupId != null) {
            List<AuditFeedback> feedbacks = auditFeedbackMapper.findLatestWithoutDurationByGroupId(groupId, 10);
            if (!feedbacks.isEmpty()) {
                long perRule = durationMs / feedbacks.size();
                for (AuditFeedback f : feedbacks) {
                    f.setDurationMs(perRule);
                    auditFeedbackMapper.updateById(f);
                }
                return;
            }
        }

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

    public List<Map<String, Object>> getDailyStats() {
        return getDailyStats(null, null);
    }

    public List<Map<String, Object>> getDailyStats(String startDate, String endDate) {
        List<AuditDailyStats> dailyList;

        if (hasDateRange(startDate, endDate)) {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            dailyList = auditDailyStatsMapper.findBetween(start, end);
        } else {
            LocalDate sevenDaysAgo = LocalDate.now().minusDays(6);
            dailyList = auditDailyStatsMapper.findSince(sevenDaysAgo);
        }

        Map<LocalDate, AuditDailyStats> map = dailyList.stream()
                .collect(Collectors.toMap(AuditDailyStats::getStatDate, d -> d));

        List<Map<String, Object>> result = new ArrayList<>();
        if (hasDateRange(startDate, endDate)) {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                AuditDailyStats ds = map.get(date);
                Map<String, Object> item = buildDailyItem(date, ds);
                result.add(item);
            }
        } else {
            for (int i = 6; i >= 0; i--) {
                LocalDate date = LocalDate.now().minusDays(i);
                AuditDailyStats ds = map.get(date);
                Map<String, Object> item = buildDailyItem(date, ds);
                result.add(item);
            }
        }
        return result;
    }

    private Map<String, Object> buildDailyItem(LocalDate date, AuditDailyStats ds) {
        Map<String, Object> item = new HashMap<>();
        item.put("date", date.format(DateTimeFormatter.ofPattern("MM-dd")));
        item.put("count", ds != null ? ds.getCount() : 0);
        item.put("avgDurationMs", (ds != null && ds.getCount() > 0)
                ? ds.getTotalDurationMs() / ds.getCount() : 0);
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

        long totalCount = feedbacks.size();
        long failCount = feedbacks.stream()
                .filter(f -> !Boolean.TRUE.equals(f.getPass()) && !Boolean.TRUE.equals(f.getSkipped()))
                .count();
        long totalDuration = feedbacks.stream()
                .mapToLong(f -> f.getDurationMs() != null ? f.getDurationMs() : 0)
                .sum();

        result.put("totalAuditCount", totalCount);
        result.put("failCount", failCount);

        long ruleCount = ruleMapper.findIdsByGroupId(groupId).size();
        long auditRuns = ruleCount > 0 ? totalCount / ruleCount : 0;
        result.put("avgDurationMs", auditRuns > 0 ? totalDuration / auditRuns : 0);

        return result;
    }

    private boolean hasDateRange(String startDate, String endDate) {
        return startDate != null && !startDate.isEmpty()
                && endDate != null && !endDate.isEmpty();
    }

    private String getTodayDate() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }
}
