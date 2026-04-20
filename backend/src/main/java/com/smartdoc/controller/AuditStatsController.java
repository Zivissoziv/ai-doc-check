package com.smartdoc.controller;

import com.smartdoc.entity.AuditStats;
import com.smartdoc.service.AuditStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuditStatsController {

    private final AuditStatsService auditStatsService;

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        AuditStats stats = auditStatsService.getStats();
        Map<String, Object> response = new HashMap<>();
        response.put("totalCount", stats.getTotalCount());
        response.put("todayCount", stats.getTodayCount());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/stats/increment")
    public ResponseEntity<Map<String, Object>> increment() {
        AuditStats stats = auditStatsService.increment();
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("totalCount", stats.getTotalCount());
        response.put("todayCount", stats.getTodayCount());
        return ResponseEntity.ok(response);
    }
}