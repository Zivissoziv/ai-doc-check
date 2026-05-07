package com.smartdoc.controller;

import com.smartdoc.dto.ApiConfigDto;
import com.smartdoc.service.ApiConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/config/api")
@RequiredArgsConstructor
public class ApiConfigController {

    private final ApiConfigService apiConfigService;

    @GetMapping
    public ResponseEntity<ApiConfigDto> getApiConfig() {
        ApiConfigDto config = apiConfigService.getApiConfig();
        return ResponseEntity.ok(config);
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> updateApiConfig(@Valid @RequestBody ApiConfigDto dto) {
        apiConfigService.updateApiConfig(dto);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "API配置已更新");
        
        return ResponseEntity.ok(response);
    }
}