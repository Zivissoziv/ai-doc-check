package com.smartdoc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdoc.entity.AuditOrderFeedback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AuditOrderFeedbackMapper extends BaseMapper<AuditOrderFeedback> {

    @Select("SELECT COUNT(*) FROM audit_order_feedback WHERE rule_id = #{ruleId} AND pass = #{pass}")
    Long countByRuleIdAndPass(@Param("ruleId") Long ruleId, @Param("pass") Boolean pass);

    @Select("SELECT COUNT(*) FROM audit_order_feedback WHERE rule_id = #{ruleId} AND feedback_type = #{feedbackType}")
    Long countByRuleIdAndFeedbackType(@Param("ruleId") Long ruleId, @Param("feedbackType") String feedbackType);

    @Select("SELECT * FROM audit_order_feedback WHERE rule_id = #{ruleId} AND feedback_type IS NOT NULL ORDER BY created_at DESC LIMIT #{limit}")
    List<AuditOrderFeedback> findRecentFeedbacks(@Param("ruleId") Long ruleId, @Param("limit") int limit);

    @Select("<script>SELECT * FROM audit_order_feedback WHERE rule_id IN <foreach collection='ruleIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<AuditOrderFeedback> findByRuleIds(@Param("ruleIds") List<Long> ruleIds);

    @Select("SELECT * FROM audit_order_feedback WHERE group_id = #{groupId} ORDER BY created_at DESC")
    List<AuditOrderFeedback> findByGroupId(@Param("groupId") String groupId);

    @Select("<script>SELECT * FROM audit_order_feedback WHERE group_id = #{groupId} " +
            "<if test='startDate != null'> AND created_at &gt;= #{startDate} </if>" +
            "<if test='endDate != null'> AND created_at &lt; #{endDate} </if>" +
            " ORDER BY created_at DESC</script>")
    List<AuditOrderFeedback> findByGroupIdBetween(@Param("groupId") String groupId,
                                                   @Param("startDate") String startDate,
                                                   @Param("endDate") String endDate);

    @Select("<script>SELECT COUNT(*) FROM audit_order_feedback WHERE rule_id = #{ruleId} AND (skipped IS NULL OR skipped = 0)" +
            "<if test='startDate != null'> AND DATE(created_at) &gt;= #{startDate}</if>" +
            "<if test='endDate != null'> AND DATE(created_at) &lt;= #{endDate}</if></script>")
    Long countByRuleIdNonSkipped(@Param("ruleId") Long ruleId,
                                  @Param("startDate") String startDate,
                                  @Param("endDate") String endDate);

    @Select("<script>SELECT COUNT(*) FROM audit_order_feedback WHERE rule_id = #{ruleId} AND pass = 1" +
            "<if test='startDate != null'> AND DATE(created_at) &gt;= #{startDate}</if>" +
            "<if test='endDate != null'> AND DATE(created_at) &lt;= #{endDate}</if></script>")
    Long countByRuleIdPass(@Param("ruleId") Long ruleId,
                            @Param("startDate") String startDate,
                            @Param("endDate") String endDate);

    @Select("<script>SELECT * FROM audit_order_feedback WHERE rule_id = #{ruleId} AND pass = 0 AND (skipped IS NULL OR skipped = 0)" +
            "<if test='startDate != null'> AND DATE(created_at) &gt;= #{startDate}</if>" +
            "<if test='endDate != null'> AND DATE(created_at) &lt;= #{endDate}</if>" +
            " ORDER BY created_at DESC LIMIT #{limit}</script>")
    List<AuditOrderFeedback> findFailuresByRuleId(@Param("ruleId") Long ruleId,
                                                   @Param("startDate") String startDate,
                                                   @Param("endDate") String endDate,
                                                   @Param("limit") int limit);

    @Select("SELECT COUNT(DISTINCT audit_batch_no) FROM audit_order_feedback WHERE skipped = 0")
    Long countTotalBatches();

    @Select("SELECT COUNT(DISTINCT audit_batch_no) FROM audit_order_feedback WHERE skipped = 0 AND DATE(created_at) = CURDATE()")
    Long countTodayBatches();

    @Select("<script>SELECT COUNT(DISTINCT audit_batch_no) FROM audit_order_feedback WHERE skipped = 0 " +
            "AND DATE(created_at) &gt;= #{startDate} AND DATE(created_at) &lt;= #{endDate}</script>")
    Long countBatchesBetween(@Param("startDate") String startDate, @Param("endDate") String endDate);

    @Select("SELECT DATE(created_at) AS statDate, COUNT(DISTINCT audit_batch_no) AS count, " +
            "COALESCE(AVG(duration_ms), 0) AS avgDurationMs " +
            "FROM audit_order_feedback WHERE skipped = 0 AND DATE(created_at) >= #{startDate} " +
            "GROUP BY DATE(created_at) ORDER BY DATE(created_at) ASC")
    List<DailyStatsRow> findDailyStatsSince(@Param("startDate") String startDate);

    @Select("SELECT DATE(created_at) AS statDate, COUNT(DISTINCT audit_batch_no) AS count, " +
            "COALESCE(AVG(duration_ms), 0) AS avgDurationMs " +
            "FROM audit_order_feedback WHERE skipped = 0 AND DATE(created_at) >= #{startDate} " +
            "AND DATE(created_at) <= #{endDate} " +
            "GROUP BY DATE(created_at) ORDER BY DATE(created_at) ASC")
    List<DailyStatsRow> findDailyStatsBetween(@Param("startDate") String startDate, @Param("endDate") String endDate);

    @Select("SELECT COUNT(DISTINCT audit_batch_no) FROM audit_order_feedback WHERE skipped = 0 AND audit_batch_no LIKE '%\\_async'")
    Long countAsyncBatches();

    @Select("SELECT COUNT(DISTINCT audit_batch_no) FROM audit_order_feedback WHERE skipped = 0 AND audit_batch_no NOT LIKE '%\\_async'")
    Long countClickBatches();

    @Select("<script>SELECT COUNT(DISTINCT audit_batch_no) FROM audit_order_feedback WHERE skipped = 0 AND audit_batch_no LIKE '%\\_async'" +
            "<if test='startDate != null'> AND DATE(created_at) &gt;= #{startDate}</if>" +
            "<if test='endDate != null'> AND DATE(created_at) &lt;= #{endDate}</if></script>")
    Long countAsyncBatchesBetween(@Param("startDate") String startDate, @Param("endDate") String endDate);

    @Select("<script>SELECT COUNT(DISTINCT audit_batch_no) FROM audit_order_feedback WHERE skipped = 0 AND audit_batch_no NOT LIKE '%\\_async'" +
            "<if test='startDate != null'> AND DATE(created_at) &gt;= #{startDate}</if>" +
            "<if test='endDate != null'> AND DATE(created_at) &lt;= #{endDate}</if></script>")
    Long countClickBatchesBetween(@Param("startDate") String startDate, @Param("endDate") String endDate);

    @Select("<script>SELECT * FROM audit_order_feedback WHERE audit_batch_no IN " +
            "<foreach collection='batchNos' item='batchNo' open='(' separator=',' close=')'>#{batchNo}</foreach> " +
            "ORDER BY created_at DESC, id DESC</script>")
    List<AuditOrderFeedback> findByBatchNos(@Param("batchNos") List<String> batchNos);

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
