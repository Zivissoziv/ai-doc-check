package com.smartdoc.controller;

import com.smartdoc.dto.RuleGroupDto;
import com.smartdoc.dto.RuleDto;
import com.smartdoc.service.RuleGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/config/rules")
@RequiredArgsConstructor
public class RuleGroupController {

    private final RuleGroupService ruleGroupService;

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
    public ResponseEntity<List<RuleDto>> getRuleGroup(@PathVariable String groupId) {
        List<RuleDto> rules = ruleGroupService.getRulesByGroupId(groupId);
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

    @PutMapping("/{groupId}")
    public ResponseEntity<Map<String, Object>> updateRuleGroup(
            @PathVariable String groupId,
            @Valid @RequestBody RuleGroupDto dto) {
        
        ruleGroupService.updateRuleGroup(groupId, dto);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{groupId}")
    public ResponseEntity<Map<String, Object>> deleteRuleGroup(@PathVariable String groupId) {
        ruleGroupService.deleteRuleGroup(groupId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        
        return ResponseEntity.ok(response);
    }
}