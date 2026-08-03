package com.smartdoc.controller;

import com.smartdoc.service.AuditStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuditStatsController {

    private final AuditStatsService auditStatsService;

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false, defaultValue = "document") String auditType) {
        return ResponseEntity.ok(auditStatsService.getStats(startDate, endDate, auditType));
    }

    @PostMapping("/stats/increment")
    public ResponseEntity<Map<String, Object>> increment() {
        auditStatsService.increment();
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/stats/duration")
    public ResponseEntity<Map<String, Object>> recordDuration(@RequestBody Map<String, Object> body) {
        Long durationMs = Long.valueOf(body.getOrDefault("durationMs", 0).toString());
        String groupId = (String) body.getOrDefault("groupId", null);
        auditStatsService.recordAuditDuration(durationMs, groupId);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/stats/daily")
    public ResponseEntity<List<Map<String, Object>>> getDailyStats(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false, defaultValue = "document") String auditType) {
        return ResponseEntity.ok(auditStatsService.getDailyStats(startDate, endDate, auditType));
    }

    @GetMapping("/stats/sources")
    public ResponseEntity<Map<String, Object>> getSourceStats(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false, defaultValue = "document") String auditType) {
        return ResponseEntity.ok(auditStatsService.getSourceStats(startDate, endDate, auditType));
    }

    @GetMapping("/stats/group/{groupId}")
    public ResponseEntity<Map<String, Object>> getGroupStats(
            @PathVariable String groupId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false, defaultValue = "document") String auditType) {
        return ResponseEntity.ok(auditStatsService.getGroupStats(groupId, startDate, endDate, auditType));
    }
}
