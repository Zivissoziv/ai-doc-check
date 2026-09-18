package com.smartdoc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdoc.entity.PromptOverride;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PromptOverrideMapper extends BaseMapper<PromptOverride> {

    @Select("SELECT * FROM prompt_override WHERE prompt_key = #{promptKey} LIMIT 1")
    PromptOverride findByKey(@Param("promptKey") String promptKey);

    @org.apache.ibatis.annotations.Delete("DELETE FROM prompt_override WHERE prompt_key = #{promptKey}")
    int deleteByKey(@Param("promptKey") String promptKey);
}
