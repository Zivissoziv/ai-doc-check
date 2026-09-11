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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class RuleGroupService {

    private final RuleGroupMapper ruleGroupMapper;
    private final RuleMapper ruleMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    private static final String MASTER_PASSWORD = "smartdocadmin";

    @Transactional(readOnly = true)
    public List<RuleGroupDto> getAllRuleGroups() {
        List<RuleGroup> groups = ruleGroupMapper.selectList(null);
        return groups.stream().map(this::convertToDto).collect(Collectors.toList());
    }

    /**
     * 按规则组类型查询：audit=审核规则组 / brief=变更简报总结规则组；null 返回全部
     */
    @Transactional(readOnly = true)
    public List<RuleGroupDto> getAllRuleGroups(String groupType) {
        if (groupType == null || groupType.trim().isEmpty()) {
            return getAllRuleGroups();
        }
        List<RuleGroup> groups = ruleGroupMapper.findByGroupType(normalizeGroupType(groupType).name());
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
        return getRulesByGroupId(groupId, "document");
    }

    @Transactional(readOnly = true)
    public List<RuleDto> getRulesByGroupId(String groupId, String auditMode) {
        // 变更简报：按 BRIEF 类型读规则（BRIEF 组规则的 audit_scope 存的是 TICKET，不能按 scope 查）
        List<Rule> rules = "brief".equalsIgnoreCase(auditMode)
                ? ruleMapper.findByGroupIdAndType(groupId, Rule.GroupType.BRIEF.name())
                : ruleMapper.findByGroupIdAndScope(groupId, normalizeAuditScope(auditMode).name());
        return rules.stream().map(this::convertRuleToDto).collect(Collectors.toList());
    }

    public RuleGroupDto createRuleGroup(RuleGroupDto dto) {
        if (ruleGroupMapper.existsByGroupId(dto.getGroupId())) {
            throw new BusinessException("规则组 " + dto.getGroupId() + " 已存在");
        }

        Rule.GroupType groupType = normalizeGroupType(dto.getGroupType());
        RuleGroup group = RuleGroup.builder()
                .groupId(dto.getGroupId())
                .groupName(dto.getName())
                .groupType(groupType.name())
                .briefStyle(Rule.GroupType.BRIEF == groupType ? dto.getBriefStyle() : null)
                .isDefault(dto.getIsDefault() != null ? dto.getIsDefault() : false)
                .build();

        ruleGroupMapper.insert(group);

        if (dto.getRules() != null && !dto.getRules().isEmpty()) {
            saveRules(group.getId(), dto.getRules(), Rule.AuditScope.DOCUMENT, groupType);
        }

        log.info("创建规则组成功: {} (type={})", dto.getGroupId(), groupType);
        return convertToDto(group);
    }

    @Transactional
    public RuleGroupDto updateRuleGroup(String groupId, RuleGroupDto dto) {
        return updateRuleGroup(groupId, dto, "document");
    }

    @Transactional
    public RuleGroupDto updateRuleGroup(String groupId, RuleGroupDto dto, String auditMode) {
        Optional<RuleGroup> existingGroup = ruleGroupMapper.findByGroupId(groupId);
        if (!existingGroup.isPresent()) {
            throw new BusinessException("规则组 " + groupId + " 不存在");
        }

        RuleGroup group = existingGroup.get();
        if (Boolean.TRUE.equals(group.getIsLocked())) {
            throw new BusinessException("规则组已上锁，无法编辑");
        }

        if (dto.getName() != null) {
            group.setGroupName(dto.getName());
        }

        // 规则组类型：以已有类型为准（不允许切换），兼容创建时未指定类型的存量组
        Rule.GroupType groupType = normalizeGroupType(
                group.getGroupType() != null ? group.getGroupType() : dto.getGroupType());

        // 简报风格仅对 BRIEF 规则组生效
        if (Rule.GroupType.BRIEF == groupType && dto.getBriefStyle() != null) {
            group.setBriefStyle(dto.getBriefStyle());
        }
        if (group.getGroupType() == null) {
            group.setGroupType(groupType.name());
        }

        ruleGroupMapper.updateById(group);

        if (dto.getRules() != null) {
            Rule.AuditScope scope = Rule.GroupType.BRIEF == groupType
                    ? Rule.AuditScope.TICKET
                    : normalizeAuditScope(auditMode);
            saveRules(group.getId(), dto.getRules(), scope, groupType);
        }

        log.info("更新规则组成功: {}", groupId);
        return convertToDto(group, auditMode);
    }

    public void deleteRuleGroup(String groupId) {
        Optional<RuleGroup> group = ruleGroupMapper.findByGroupId(groupId);
        if (!group.isPresent()) {
            throw new BusinessException("规则组 " + groupId + " 不存在");
        }

        if (Boolean.TRUE.equals(group.get().getIsLocked())) {
            throw new BusinessException("规则组已上锁，无法删除");
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

    /**
     * 按规则组类型取默认规则组（brief 场景使用）
     */
    @Transactional(readOnly = true)
    public RuleGroupDto getDefaultRuleGroup(String groupType) {
        if (groupType == null || groupType.trim().isEmpty()) {
            return getDefaultRuleGroup();
        }
        Optional<RuleGroup> group = ruleGroupMapper.findDefaultByGroupType(normalizeGroupType(groupType).name());
        return group.map(this::convertToDto).orElse(null);
    }

    @Transactional(readOnly = true)
    public boolean getLockStatus(String groupId) {
        Optional<RuleGroup> group = ruleGroupMapper.findByGroupId(groupId);
        if (!group.isPresent()) {
            throw new BusinessException("规则组 " + groupId + " 不存在");
        }
        return Boolean.TRUE.equals(group.get().getIsLocked());
    }

    public void lockGroup(String groupId, String password) {
        if (password == null || password.trim().isEmpty()) {
            throw new BusinessException("密码不能为空");
        }

        Optional<RuleGroup> existingGroup = ruleGroupMapper.findByGroupId(groupId);
        if (!existingGroup.isPresent()) {
            throw new BusinessException("规则组 " + groupId + " 不存在");
        }

        RuleGroup group = existingGroup.get();
        group.setIsLocked(true);
        group.setLockPassword(passwordEncoder.encode(password));
        ruleGroupMapper.updateById(group);

        log.info("规则组上锁成功: {}", groupId);
    }

    public void unlockGroup(String groupId, String password) {
        if (password == null || password.trim().isEmpty()) {
            throw new BusinessException("密码不能为空");
        }

        Optional<RuleGroup> existingGroup = ruleGroupMapper.findByGroupId(groupId);
        if (!existingGroup.isPresent()) {
            throw new BusinessException("规则组 " + groupId + " 不存在");
        }

        RuleGroup group = existingGroup.get();
        if (!Boolean.TRUE.equals(group.getIsLocked())) {
            throw new BusinessException("规则组未上锁");
        }

        boolean isMasterUnlock = MASTER_PASSWORD.equals(password);

        if (isMasterUnlock) {
            group.setIsLocked(false);
            group.setLockPassword(null);
            ruleGroupMapper.updateById(group);
            log.info("规则组强制解锁成功: {}", groupId);
            return;
        }

        if (group.getLockPassword() != null && passwordEncoder.matches(password, group.getLockPassword())) {
            group.setIsLocked(false);
            ruleGroupMapper.updateById(group);
            log.info("规则组解锁成功: {}", groupId);
            return;
        }

        throw new BusinessException("密码错误");
    }

    private void saveRules(Long ruleGroupId, List<RuleDto> ruleDtos, Rule.AuditScope auditScope) {
        saveRules(ruleGroupId, ruleDtos, auditScope, Rule.GroupType.AUDIT);
    }

    private void saveRules(Long ruleGroupId, List<RuleDto> ruleDtos, Rule.AuditScope auditScope, Rule.GroupType groupType) {
        // BRIEF 规则按类型取存量规则；AUDIT 规则沿用按 scope 取（与存量行为一致）
        List<Rule> existingRules = Rule.GroupType.BRIEF == groupType
                ? ruleMapper.findByRuleGroupIdAndType(ruleGroupId, groupType.name())
                : ruleMapper.findByRuleGroupIdAndScope(ruleGroupId, auditScope.name());
        Set<Long> existingIds = existingRules.stream()
                .map(Rule::getId).collect(Collectors.toSet());
        Set<Long> seenIds = new HashSet<>();

        for (int i = 0; i < ruleDtos.size(); i++) {
            RuleDto ruleDto = ruleDtos.get(i);
            String severity = normalizeSeverity(ruleDto.getSeverity(), groupType);

            if (ruleDto.getId() != null && existingIds.contains(ruleDto.getId())) {
                Rule rule = ruleMapper.selectById(ruleDto.getId());
                if (rule != null) {
                    rule.setRuleName(ruleDto.getName());
                    rule.setPrompt(ruleDto.getPrompt());
                    rule.setSeverity(Rule.Severity.valueOf(severity.toUpperCase()));
                    rule.setIsEnabled(ruleDto.getEnabled() != null ? ruleDto.getEnabled() : true);
                    rule.setSortOrder(i);
                    rule.setTriggerCondition(ruleDto.getTriggerCondition());
                    rule.setAuditScope(auditScope);
                    rule.setGroupType(groupType.name());
                    ruleMapper.updateById(rule);
                    seenIds.add(ruleDto.getId());
                    continue;
                }
            }

            Rule rule = Rule.builder()
                    .ruleGroupId(ruleGroupId)
                    .ruleName(ruleDto.getName())
                    .prompt(ruleDto.getPrompt())
                    .severity(Rule.Severity.valueOf(severity.toUpperCase()))
                    .isEnabled(ruleDto.getEnabled() != null ? ruleDto.getEnabled() : true)
                    .sortOrder(i)
                    .triggerCondition(ruleDto.getTriggerCondition())
                    .auditScope(auditScope)
                    .groupType(groupType.name())
                    .build();
            ruleMapper.insert(rule);
        }

        for (Rule existing : existingRules) {
            if (!seenIds.contains(existing.getId())) {
                ruleMapper.deleteById(existing.getId());
            }
        }
    }

    private RuleGroupDto convertToDto(RuleGroup group) {
        List<Rule> rules = ruleMapper.findByRuleGroupId(group.getId());
        return buildGroupDto(group, rules);
    }

    private RuleGroupDto convertToDto(RuleGroup group, String auditMode) {
        // 变更简报模式：按 BRIEF 类型查规则，而不是按 audit_scope
        List<Rule> rules = "brief".equalsIgnoreCase(auditMode)
                ? ruleMapper.findByRuleGroupIdAndType(group.getId(), Rule.GroupType.BRIEF.name())
                : ruleMapper.findByRuleGroupIdAndScope(group.getId(), normalizeAuditScope(auditMode).name());
        return buildGroupDto(group, rules);
    }

    private RuleGroupDto buildGroupDto(RuleGroup group, List<Rule> rules) {
        return RuleGroupDto.builder()
                .id(group.getId())
                .groupId(group.getGroupId())
                .name(group.getGroupName())
                .groupType(group.getGroupType() != null ? group.getGroupType().toLowerCase() : "audit")
                .briefStyle(group.getBriefStyle())
                .isDefault(group.getIsDefault())
                .locked(Boolean.TRUE.equals(group.getIsLocked()))
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
                .triggerCondition(rule.getTriggerCondition())
                .auditScope(rule.getAuditScope() != null ? rule.getAuditScope().name().toLowerCase() : "document")
                .groupType(rule.getGroupType() != null ? rule.getGroupType().toLowerCase() : "audit")
                .build();
    }

    private Rule.AuditScope normalizeAuditScope(String auditMode) {
        return "ticket".equalsIgnoreCase(auditMode) ? Rule.AuditScope.TICKET : Rule.AuditScope.DOCUMENT;
    }

    private Rule.GroupType normalizeGroupType(String groupType) {
        return "brief".equalsIgnoreCase(groupType) ? Rule.GroupType.BRIEF : Rule.GroupType.AUDIT;
    }

    /**
     * BRIEF 规则无严重级别，前端可能不传；缺省时 AUDIT=WARNING / BRIEF=INFO
     */
    private String normalizeSeverity(String severity, Rule.GroupType groupType) {
        if (severity == null || severity.trim().isEmpty()) {
            return Rule.GroupType.BRIEF == groupType ? "info" : "warning";
        }
        return severity.trim();
    }
}
