package com.smartdoc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdoc.entity.Rule;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RuleMapper extends BaseMapper<Rule> {

    @Select("SELECT * FROM rule WHERE rule_group_id = #{ruleGroupId} ORDER BY sort_order")
    List<Rule> findByRuleGroupId(@Param("ruleGroupId") Long ruleGroupId);

    @Select("SELECT r.* FROM rule r JOIN rule_group rg ON r.rule_group_id = rg.id WHERE rg.group_id = #{groupId} ORDER BY r.sort_order")
    List<Rule> findByGroupId(@Param("groupId") String groupId);

    @Select("SELECT r.* FROM rule r JOIN rule_group rg ON r.rule_group_id = rg.id WHERE rg.group_id = #{groupId} AND r.is_enabled = true ORDER BY r.sort_order")
    List<Rule> findEnabledByGroupId(@Param("groupId") String groupId);

    @Delete("DELETE FROM rule WHERE rule_group_id = #{ruleGroupId}")
    void deleteByRuleGroupId(@Param("ruleGroupId") Long ruleGroupId);

    @Select("SELECT r.id FROM rule r JOIN rule_group rg ON r.rule_group_id = rg.id WHERE rg.group_id = #{groupId}")
    List<Long> findIdsByGroupId(@Param("groupId") String groupId);
}