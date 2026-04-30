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
}
