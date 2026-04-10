package com.smartdoc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdoc.entity.Template;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

@Mapper
public interface TemplateMapper extends BaseMapper<Template> {

    @Select("SELECT * FROM template WHERE is_default = true LIMIT 1")
    Optional<Template> findByIsDefaultTrue();

    @Select("SELECT * FROM template WHERE template_name = #{templateName}")
    Optional<Template> findByTemplateName(String templateName);
}