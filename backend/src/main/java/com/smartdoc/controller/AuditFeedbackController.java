package com.smartdoc.controller;

import com.smartdoc.dto.AuditFeedbackDto;
import com.smartdoc.dto.AuditResultDto;
import com.smartdoc.dto.RuleFeedbackStatsDto;
import com.smartdoc.entity.AuditFeedback;
import com.smartdoc.service.AuditFeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class AuditFeedbackController {

    private final AuditFeedbackService auditFeedbackService;

    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> saveAuditResults(@RequestBody List<AuditResultDto> results) {
        List<AuditFeedback> saved = auditFeedbackService.saveAuditResults(results);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("ids", saved.stream().map(AuditFeedback::getId).collect(Collectors.toList()));
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> submitFeedback(@PathVariable Long id, @RequestBody AuditFeedbackDto dto) {
        AuditFeedback updated = auditFeedbackService.submitFeedback(id, dto.getFeedbackType(), dto.getReason());
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("feedback", updated);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/stats/{ruleId}")
    public ResponseEntity<RuleFeedbackStatsDto> getRuleStats(@PathVariable Long ruleId) {
        RuleFeedbackStatsDto stats = auditFeedbackService.getRuleStats(ruleId);
        return ResponseEntity.ok(stats);
    }
}
