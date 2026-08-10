package com.smartdoc.controller;

import com.smartdoc.dto.LockRequestDto;
import com.smartdoc.dto.RuleGroupDto;
import com.smartdoc.dto.RuleDto;
import com.smartdoc.dto.RuleTrainingRequestDto;
import com.smartdoc.dto.RuleTrainingResponseDto;
import com.smartdoc.service.RuleGroupService;
import com.smartdoc.service.RuleTrainingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/config/rules")
@RequiredArgsConstructor
public class RuleGroupController {

    private final RuleGroupService ruleGroupService;
    private final RuleTrainingService ruleTrainingService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllRuleGroups() {
        List<RuleGroupDto> groups = ruleGroupService.getAllRuleGroups();
        
        Map<String, Object> response = new HashMap<>();
        response.put("groups", groups);
        
        RuleGroupDto defaultGroup = ruleGroupService.getDefaultRuleGroup();
        if (defaultGroup != null) {
            response.put("defaultGroup", defaultGroup.getGroupId());
        }
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{groupId}")
    public ResponseEntity<List<RuleDto>> getRuleGroup(
            @PathVariable String groupId,
            @RequestParam(required = false, defaultValue = "document") String auditMode) {
        List<RuleDto> rules = ruleGroupService.getRulesByGroupId(groupId, auditMode);
        return ResponseEntity.ok(rules);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createRuleGroup(@Valid @RequestBody RuleGroupDto dto) {
        RuleGroupDto created = ruleGroupService.createRuleGroup(dto);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("id", created.getGroupId());
        response.put("path", "config/rules/" + created.getGroupId() + ".json");
        
        return ResponseEntity.ok(response);
    }

    @PostMapping("/train")
    public ResponseEntity<RuleTrainingResponseDto> trainRules(@Valid @RequestBody RuleTrainingRequestDto dto) {
        return ResponseEntity.ok(ruleTrainingService.trainRules(dto.getReviewReport(), dto.getAuditMode()));
    }

    @PutMapping("/{groupId}")
    public ResponseEntity<RuleGroupDto> updateRuleGroup(
            @PathVariable String groupId,
            @RequestParam(required = false, defaultValue = "document") String auditMode,
            @Valid @RequestBody RuleGroupDto dto) {

        RuleGroupDto updated = ruleGroupService.updateRuleGroup(groupId, dto, auditMode);

        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{groupId}")
    public ResponseEntity<Map<String, Object>> deleteRuleGroup(@PathVariable String groupId) {
        ruleGroupService.deleteRuleGroup(groupId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{groupId}/locked")
    public ResponseEntity<Map<String, Object>> getLockStatus(@PathVariable String groupId) {
        boolean locked = ruleGroupService.getLockStatus(groupId);
        Map<String, Object> response = new HashMap<>();
        response.put("locked", locked);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{groupId}/lock")
    public ResponseEntity<Map<String, Object>> lockGroup(@PathVariable String groupId, @RequestBody LockRequestDto dto) {
        ruleGroupService.lockGroup(groupId, dto.getPassword());
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{groupId}/unlock")
    public ResponseEntity<Map<String, Object>> unlockGroup(@PathVariable String groupId, @RequestBody LockRequestDto dto) {
        ruleGroupService.unlockGroup(groupId, dto.getPassword());
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        return ResponseEntity.ok(response);
    }
}
