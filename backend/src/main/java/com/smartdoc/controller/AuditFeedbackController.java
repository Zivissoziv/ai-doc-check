package com.smartdoc.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdoc.dto.AuditFeedbackDto;
import com.smartdoc.dto.AuditIssueDto;
import com.smartdoc.dto.AuditResultDto;
import com.smartdoc.dto.RuleFeedbackStatsDto;
import com.smartdoc.entity.AuditFeedback;
import com.smartdoc.service.AuditFeedbackService;
import com.smartdoc.service.AuditTicketRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class AuditFeedbackController {

    private final AuditFeedbackService auditFeedbackService;
    private final AuditTicketRecordService auditTicketRecordService;
    private final ObjectMapper objectMapper;

    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> saveAuditResults(
            @RequestBody List<AuditResultDto> results,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) Long durationMs,
            @RequestParam(required = false) String ticketId,
            @RequestParam(required = false) String ts) {
        List<AuditFeedback> saved = auditFeedbackService.saveAuditResults(results, groupId, durationMs);
        // 如果传入了 ticketId 和 ts，同步保存到 audit_ticket_record
        if (ticketId != null && ts != null && !ticketId.isEmpty() && !ts.isEmpty()) {
            String batchNo = saved != null && !saved.isEmpty()
                    ? saved.get(0).getAuditBatchNo()
                    : java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            log.info("保存审核结果时同步保存工单记录: ticketId={}, ts={}, batchNo={}", ticketId, ts, batchNo);
            auditTicketRecordService.saveRecord(ticketId, ts, batchNo, "ticket_" + ticketId);
        } else {
            log.warn("跳过保存工单记录: ticketId={}, ts={}", ticketId, ts);
        }
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
    public ResponseEntity<RuleFeedbackStatsDto> getRuleStats(
            @PathVariable Long ruleId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        RuleFeedbackStatsDto stats = auditFeedbackService.getRuleStats(ruleId, startDate, endDate);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/failures/{ruleId}")
    public ResponseEntity<List<Map<String, Object>>> getRuleFailures(
            @PathVariable Long ruleId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        List<AuditFeedback> feedbacks = auditFeedbackService.getRuleFailures(ruleId, startDate, endDate, 30);
        List<Map<String, Object>> result = new ArrayList<>();
        for (AuditFeedback f : feedbacks) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", f.getId());
            item.put("createdAt", f.getCreatedAt());
            try {
                AuditResultDto dto = objectMapper.readValue(f.getResultsJson(), AuditResultDto.class);
                item.put("summary", dto.getSummary());
                item.put("issues", dto.getIssues() != null ? dto.getIssues() : new ArrayList<>());
                item.put("confidence", dto.getConfidence());
            } catch (JsonProcessingException e) {
                log.warn("解析失败记录 results_json 失败: id={}", f.getId());
                item.put("summary", f.getResultsJson());
                item.put("issues", new ArrayList<>());
                item.put("confidence", null);
            }
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }
}
