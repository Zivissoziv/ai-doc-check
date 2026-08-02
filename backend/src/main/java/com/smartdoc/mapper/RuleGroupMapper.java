package com.smartdoc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdoc.entity.RuleGroup;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

@Mapper
public interface RuleGroupMapper extends BaseMapper<RuleGroup> {

    @Select("SELECT * FROM rule_group WHERE group_id = #{groupId}")
    Optional<RuleGroup> findByGroupId(String groupId);

    @Select("SELECT COUNT(*) > 0 FROM rule_group WHERE group_id = #{groupId}")
    boolean existsByGroupId(String groupId);

    @Select("SELECT * FROM rule_group WHERE is_default = true LIMIT 1")
    Optional<RuleGroup> findByIsDefaultTrue();

    @Delete("DELETE FROM rule_group WHERE group_id = #{groupId}")
    void deleteByGroupId(String groupId);
}
