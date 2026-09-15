package com.smartdoc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdoc.entity.PermissionGroup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

@Mapper
public interface PermissionGroupMapper extends BaseMapper<PermissionGroup> {

    @Select("SELECT * FROM permission_group WHERE perm_key = #{permKey} LIMIT 1")
    Optional<PermissionGroup> findByPermKey(@Param("permKey") String permKey);

    @Select("SELECT * FROM permission_group ORDER BY created_at, id")
    List<PermissionGroup> findAllOrdered();
}
