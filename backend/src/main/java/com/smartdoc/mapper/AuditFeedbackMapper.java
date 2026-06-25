package com.smartdoc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdoc.entity.AuditFeedback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AuditFeedbackMapper extends BaseMapper<AuditFeedback> {

    @Select("SELECT COUNT(*) FROM audit_feedback WHERE rule_id = #{ruleId} AND pass = #{pass}")
    Long countByRuleIdAndPass(@Param("ruleId") Long ruleId, @Param("pass") Boolean pass);

    @Select("SELECT COUNT(*) FROM audit_feedback WHERE rule_id = #{ruleId} AND feedback_type = #{feedbackType}")
    Long countByRuleIdAndFeedbackType(@Param("ruleId") Long ruleId, @Param("feedbackType") String feedbackType);

    @Select("SELECT * FROM audit_feedback WHERE rule_id = #{ruleId} AND feedback_type IS NOT NULL ORDER BY created_at DESC LIMIT #{limit}")
    List<AuditFeedback> findRecentFeedbacks(@Param("ruleId") Long ruleId, @Param("limit") int limit);

    @Select("<script>SELECT * FROM audit_feedback WHERE rule_id IN <foreach collection='ruleIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<AuditFeedback> findByRuleIds(@Param("ruleIds") List<Long> ruleIds);

    @Select("<script>SELECT * FROM audit_feedback WHERE rule_id IN <foreach collection='ruleIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> AND (duration_ms IS NULL OR duration_ms = 0) ORDER BY created_at DESC LIMIT #{limit}</script>")
    List<AuditFeedback> findLatestWithoutDuration(@Param("ruleIds") List<Long> ruleIds, @Param("limit") int limit);

    @Select("SELECT * FROM audit_feedback WHERE group_id = #{groupId} ORDER BY created_at DESC")
    List<AuditFeedback> findByGroupId(@Param("groupId") String groupId);

    @Select("<script>SELECT * FROM audit_feedback WHERE group_id = #{groupId} " +
            "<if test='startDate != null'> AND created_at &gt;= #{startDate} </if>" +
            "<if test='endDate != null'> AND created_at &lt; #{endDate} </if>" +
            " ORDER BY created_at DESC</script>")
    List<AuditFeedback> findByGroupIdBetween(@Param("groupId") String groupId,
                                              @Param("startDate") String startDate,
                                              @Param("endDate") String endDate);

    @Select("SELECT * FROM audit_feedback WHERE group_id = #{groupId} AND (duration_ms IS NULL OR duration_ms = 0) ORDER BY created_at DESC LIMIT #{limit}")
    List<AuditFeedback> findLatestWithoutDurationByGroupId(@Param("groupId") String groupId, @Param("limit") int limit);

    @Select("<script>SELECT COUNT(*) FROM audit_feedback WHERE rule_id = #{ruleId} AND (skipped IS NULL OR skipped = 0)" +
            "<if test='startDate != null'> AND DATE(created_at) &gt;= #{startDate}</if>" +
            "<if test='endDate != null'> AND DATE(created_at) &lt;= #{endDate}</if></script>")
    Long countByRuleIdNonSkipped(@Param("ruleId") Long ruleId,
                                  @Param("startDate") String startDate,
                                  @Param("endDate") String endDate);

    @Select("<script>SELECT COUNT(*) FROM audit_feedback WHERE rule_id = #{ruleId} AND pass = 1" +
            "<if test='startDate != null'> AND DATE(created_at) &gt;= #{startDate}</if>" +
            "<if test='endDate != null'> AND DATE(created_at) &lt;= #{endDate}</if></script>")
    Long countByRuleIdPass(@Param("ruleId") Long ruleId,
                            @Param("startDate") String startDate,
                            @Param("endDate") String endDate);

    @Select("<script>SELECT COALESCE(AVG(duration_ms), 0) FROM audit_feedback WHERE rule_id = #{ruleId} AND (skipped IS NULL OR skipped = 0) AND duration_ms IS NOT NULL" +
            "<if test='startDate != null'> AND DATE(created_at) &gt;= #{startDate}</if>" +
            "<if test='endDate != null'> AND DATE(created_at) &lt;= #{endDate}</if></script>")
    Long avgDurationByRuleId(@Param("ruleId") Long ruleId,
                              @Param("startDate") String startDate,
                              @Param("endDate") String endDate);

    @Select("<script>SELECT * FROM audit_feedback WHERE rule_id = #{ruleId} AND pass = 0 AND (skipped IS NULL OR skipped = 0)" +
            "<if test='startDate != null'> AND DATE(created_at) &gt;= #{startDate}</if>" +
            "<if test='endDate != null'> AND DATE(created_at) &lt;= #{endDate}</if>" +
            " ORDER BY created_at DESC LIMIT #{limit}</script>")
    List<AuditFeedback> findFailuresByRuleId(@Param("ruleId") Long ruleId,
                                              @Param("startDate") String startDate,
                                              @Param("endDate") String endDate,
                                              @Param("limit") int limit);

    @Select("SELECT COUNT(DISTINCT audit_batch_no) FROM audit_feedback WHERE skipped = 0")
    Long countTotalBatches();

    @Select("SELECT COUNT(DISTINCT audit_batch_no) FROM audit_feedback WHERE skipped = 0 AND DATE(created_at) = CURDATE()")
    Long countTodayBatches();

    @Select("<script>SELECT COUNT(DISTINCT audit_batch_no) FROM audit_feedback WHERE skipped = 0 " +
            "AND DATE(created_at) &gt;= #{startDate} AND DATE(created_at) &lt;= #{endDate}</script>")
    Long countBatchesBetween(@Param("startDate") String startDate, @Param("endDate") String endDate);

    @Select("SELECT DATE(created_at) AS statDate, COUNT(DISTINCT audit_batch_no) AS count, " +
            "COALESCE(AVG(duration_ms), 0) AS avgDurationMs " +
            "FROM audit_feedback WHERE skipped = 0 AND DATE(created_at) >= #{startDate} " +
            "GROUP BY DATE(created_at) ORDER BY DATE(created_at) ASC")
    List<DailyStatsRow> findDailyStatsSince(@Param("startDate") String startDate);

    @Select("SELECT DATE(created_at) AS statDate, COUNT(DISTINCT audit_batch_no) AS count, " +
            "COALESCE(AVG(duration_ms), 0) AS avgDurationMs " +
            "FROM audit_feedback WHERE skipped = 0 AND DATE(created_at) >= #{startDate} " +
            "AND DATE(created_at) <= #{endDate} " +
            "GROUP BY DATE(created_at) ORDER BY DATE(created_at) ASC")
    List<DailyStatsRow> findDailyStatsBetween(@Param("startDate") String startDate, @Param("endDate") String endDate);

    class DailyStatsRow {
        private String statDate;
        private Long count;
        private Long avgDurationMs;
        public DailyStatsRow() {}
        public String getStatDate() { return statDate; }
        public void setStatDate(String statDate) { this.statDate = statDate; }
        public Long getCount() { return count; }
        public void setCount(Long count) { this.count = count; }
        public Long getAvgDurationMs() { return avgDurationMs; }
        public void setAvgDurationMs(Long avgDurationMs) { this.avgDurationMs = avgDurationMs; }
    }
}
