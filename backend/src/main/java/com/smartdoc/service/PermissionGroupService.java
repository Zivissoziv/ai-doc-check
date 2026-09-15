package com.smartdoc.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdoc.dto.PermissionGroupDto;
import com.smartdoc.entity.PermissionGroup;
import com.smartdoc.exception.BusinessException;
import com.smartdoc.mapper.PermissionGroupMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class PermissionGroupService {

    private final PermissionGroupMapper permissionGroupMapper;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<PermissionGroupDto> listAll() {
        return permissionGroupMapper.findAllOrdered().stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    /**
     * 按 URL 参数值取权限组；不存在返回 null（由调用方决定兜底行为）
     */
    @Transactional(readOnly = true)
    public PermissionGroupDto getByPermKey(String permKey) {
        if (permKey == null || permKey.trim().isEmpty()) {
            return null;
        }
        return permissionGroupMapper.findByPermKey(permKey.trim()).map(this::convertToDto).orElse(null);
    }

    public PermissionGroupDto create(PermissionGroupDto dto) {
        String permKey = dto.getPermKey() == null ? "" : dto.getPermKey().trim();
        if (permissionGroupMapper.findByPermKey(permKey).isPresent()) {
            throw new BusinessException("权限组标识 " + permKey + " 已存在");
        }

        PermissionGroup group = PermissionGroup.builder()
                .permKey(permKey)
                .permName(dto.getPermName() == null ? permKey : dto.getPermName().trim())
                .briefVisible(Boolean.TRUE.equals(dto.getBriefVisible()))
                .visibleGroupIds(serializeGroupIds(dto.getVisibleGroupIds()))
                .build();

        permissionGroupMapper.insert(group);
        log.info("创建权限组成功: {} ({})", permKey, group.getPermName());
        return convertToDto(group);
    }

    public PermissionGroupDto update(Long id, PermissionGroupDto dto) {
        PermissionGroup group = permissionGroupMapper.selectById(id);
        if (group == null) {
            throw new BusinessException("权限组不存在");
        }

        if (dto.getPermKey() != null && !dto.getPermKey().trim().isEmpty()) {
            String permKey = dto.getPermKey().trim();
            Optional<PermissionGroup> sameKey = permissionGroupMapper.findByPermKey(permKey);
            if (sameKey.isPresent() && !sameKey.get().getId().equals(id)) {
                throw new BusinessException("权限组标识 " + permKey + " 已存在");
            }
            group.setPermKey(permKey);
        }

        if (dto.getPermName() != null && !dto.getPermName().trim().isEmpty()) {
            group.setPermName(dto.getPermName().trim());
        }

        // briefVisible 为 null 视为"不改动"，避免误清开关
        if (dto.getBriefVisible() != null) {
            group.setBriefVisible(dto.getBriefVisible());
        }

        if (dto.getVisibleGroupIds() != null) {
            group.setVisibleGroupIds(serializeGroupIds(dto.getVisibleGroupIds()));
        }

        permissionGroupMapper.updateById(group);
        log.info("更新权限组成功: id={}", id);
        return convertToDto(group);
    }

    public void delete(Long id) {
        PermissionGroup group = permissionGroupMapper.selectById(id);
        if (group == null) {
            throw new BusinessException("权限组不存在");
        }
        permissionGroupMapper.deleteById(id);
        log.info("删除权限组成功: id={}", id);
    }

    private PermissionGroupDto convertToDto(PermissionGroup group) {
        return PermissionGroupDto.builder()
                .id(group.getId())
                .permKey(group.getPermKey())
                .permName(group.getPermName())
                .briefVisible(Boolean.TRUE.equals(group.getBriefVisible()))
                .visibleGroupIds(deserializeGroupIds(group.getVisibleGroupIds()))
                .build();
    }

    private String serializeGroupIds(List<String> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            // 空 = 不限制（全部可见）
            return null;
        }
        List<String> cleaned = groupIds.stream()
                .filter(id -> id != null && !id.trim().isEmpty())
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(cleaned);
        } catch (Exception e) {
            throw new BusinessException("保存可见规则组失败: " + e.getMessage());
        }
    }

    private List<String> deserializeGroupIds(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("解析可见规则组失败，按全部可见处理: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
