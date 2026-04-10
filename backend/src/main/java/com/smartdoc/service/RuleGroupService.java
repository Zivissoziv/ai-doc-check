package com.smartdoc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdoc.dto.RuleDto;
import com.smartdoc.dto.RuleGroupDto;
import com.smartdoc.entity.Rule;
import com.smartdoc.entity.RuleGroup;
import com.smartdoc.exception.BusinessException;
import com.smartdoc.mapper.RuleGroupMapper;
import com.smartdoc.mapper.RuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class RuleGroupService {

    private final RuleGroupMapper ruleGroupMapper;
    private final RuleMapper ruleMapper;

    @Transactional(readOnly = true)
    public List<RuleGroupDto> getAllRuleGroups() {
        List<RuleGroup> groups = ruleGroupMapper.selectList(null);
        return groups.stream().map(this::convertToDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public RuleGroupDto getRuleGroupByGroupId(String groupId) {
        Optional<RuleGroup> group = ruleGroupMapper.findByGroupId(groupId);
        if (!group.isPresent()) {
            throw new BusinessException("规则组 " + groupId + " 不存在");
        }
        return convertToDto(group.get());
    }

    @Transactional(readOnly = true)
    public List<RuleDto> getRulesByGroupId(String groupId) {
        List<Rule> rules = ruleMapper.findByGroupId(groupId);
        return rules.stream().map(this::convertRuleToDto).collect(Collectors.toList());
    }

    public RuleGroupDto createRuleGroup(RuleGroupDto dto) {
        if (ruleGroupMapper.existsByGroupId(dto.getGroupId())) {
            throw new BusinessException("规则组 " + dto.getGroupId() + " 已存在");
        }

        RuleGroup group = RuleGroup.builder()
                .groupId(dto.getGroupId())
                .groupName(dto.getName())
                .isDefault(dto.getIsDefault() != null ? dto.getIsDefault() : false)
                .build();

        ruleGroupMapper.insert(group);

        if (dto.getRules() != null && !dto.getRules().isEmpty()) {
            saveRules(group.getId(), dto.getRules());
        }

        log.info("创建规则组成功: {}", dto.getGroupId());
        return convertToDto(group);
    }

    public RuleGroupDto updateRuleGroup(String groupId, RuleGroupDto dto) {
        Optional<RuleGroup> existingGroup = ruleGroupMapper.findByGroupId(groupId);
        if (!existingGroup.isPresent()) {
            throw new BusinessException("规则组 " + groupId + " 不存在");
        }

        RuleGroup group = existingGroup.get();
        if (dto.getName() != null) {
            group.setGroupName(dto.getName());
        }

        ruleGroupMapper.updateById(group);

        if (dto.getRules() != null) {
            ruleMapper.deleteByRuleGroupId(group.getId());
            saveRules(group.getId(), dto.getRules());
        }

        log.info("更新规则组成功: {}", groupId);
        return convertToDto(group);
    }

    public void deleteRuleGroup(String groupId) {
        Optional<RuleGroup> group = ruleGroupMapper.findByGroupId(groupId);
        if (!group.isPresent()) {
            throw new BusinessException("规则组 " + groupId + " 不存在");
        }

        if ("index".equals(groupId)) {
            throw new BusinessException("不能删除索引文件");
        }

        ruleMapper.deleteByRuleGroupId(group.get().getId());
        ruleGroupMapper.deleteByGroupId(groupId);
        log.info("删除规则组成功: {}", groupId);
    }

    @Transactional(readOnly = true)
    public RuleGroupDto getDefaultRuleGroup() {
        Optional<RuleGroup> group = ruleGroupMapper.findByIsDefaultTrue();
        if (!group.isPresent()) {
            List<RuleGroup> groups = ruleGroupMapper.selectList(null);
            if (!groups.isEmpty()) {
                return convertToDto(groups.get(0));
            }
            return null;
        }
        return convertToDto(group.get());
    }

    private void saveRules(Long ruleGroupId, List<RuleDto> ruleDtos) {
        for (int i = 0; i < ruleDtos.size(); i++) {
            RuleDto ruleDto = ruleDtos.get(i);
            Rule rule = Rule.builder()
                    .ruleGroupId(ruleGroupId)
                    .ruleName(ruleDto.getName())
                    .prompt(ruleDto.getPrompt())
                    .severity(Rule.Severity.valueOf(ruleDto.getSeverity().toUpperCase()))
                    .isEnabled(ruleDto.getEnabled() != null ? ruleDto.getEnabled() : true)
                    .sortOrder(i)
                    .build();
            ruleMapper.insert(rule);
        }
    }

    private RuleGroupDto convertToDto(RuleGroup group) {
        List<Rule> rules = ruleMapper.findByRuleGroupId(group.getId());
        
        return RuleGroupDto.builder()
                .id(group.getId())
                .groupId(group.getGroupId())
                .name(group.getGroupName())
                .isDefault(group.getIsDefault())
                .rules(rules.stream().map(this::convertRuleToDto).collect(Collectors.toList()))
                .build();
    }

    private RuleDto convertRuleToDto(Rule rule) {
        return RuleDto.builder()
                .id(rule.getId())
                .name(rule.getRuleName())
                .prompt(rule.getPrompt())
                .severity(rule.getSeverity() != null ? rule.getSeverity().name().toLowerCase() : "warning")
                .enabled(rule.getIsEnabled())
                .sortOrder(rule.getSortOrder())
                .build();
    }
}