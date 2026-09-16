package com.smartdoc.service;

import com.smartdoc.dto.ApiConfigDto;
import com.smartdoc.entity.ApiConfig;
import com.smartdoc.mapper.ApiConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ApiConfigService {

    private final ApiConfigMapper apiConfigMapper;

    @Transactional(readOnly = true)
    public ApiConfigDto getApiConfig() {
        ApiConfig config = apiConfigMapper.selectById(1L);
        if (config == null) {
            config = createDefaultConfig();
            return convertToDto(config);
        }
        return convertToDto(config);
    }

    public ApiConfigDto updateApiConfig(ApiConfigDto dto) {
        ApiConfig config = apiConfigMapper.selectById(1L);
        
        if (config == null) {
            config = ApiConfig.builder().id(1L).build();
        }

        if (dto.getProvider() != null) {
            config.setProvider(dto.getProvider());
        }
        if (dto.getEndpoint() != null) {
            config.setEndpoint(dto.getEndpoint());
        }
        if (dto.getApiKey() != null && !dto.getApiKey().isEmpty()) {
            config.setApiKey(dto.getApiKey());
        }
        if (dto.getModel() != null) {
            config.setModel(dto.getModel());
        }
        if (dto.getAuditRole() != null) {
            config.setAuditRole(dto.getAuditRole());
        }
        if (dto.getTicketEndpoint() != null) {
            config.setTicketEndpoint(dto.getTicketEndpoint());
        }
        if (dto.getTicketToken() != null) {
            config.setTicketToken(dto.getTicketToken());
        }
        if (dto.getOrderAuditEndpoint() != null) {
            config.setOrderAuditEndpoint(dto.getOrderAuditEndpoint());
        }
        if (dto.getOrderHttpMethod() != null && !dto.getOrderHttpMethod().trim().isEmpty()) {
            config.setOrderHttpMethod(normalizeHttpMethod(dto.getOrderHttpMethod()));
        }
        // 请求体模板允许清空（空字符串 = 清空为 NULL）
        if (dto.getOrderListBody() != null) {
            config.setOrderListBody(emptyToNull(dto.getOrderListBody()));
        }
        if (dto.getOrderDetailBody() != null) {
            config.setOrderDetailBody(emptyToNull(dto.getOrderDetailBody()));
        }

        if (config.getId() == null) {
            apiConfigMapper.insert(config);
        } else {
            apiConfigMapper.updateById(config);
        }
        
        return convertToDto(config);
    }

    private ApiConfig createDefaultConfig() {
        ApiConfig config = ApiConfig.builder()
                .id(1L)
                .provider("custom")
                .endpoint("https://api.deepseek.com/v1/chat/completions")
                .model("deepseek-chat")
                .auditRole("专业文档审核专家")
                .build();
        apiConfigMapper.insert(config);
        return config;
    }

    private ApiConfigDto convertToDto(ApiConfig config) {
        return ApiConfigDto.builder()
                .id(config.getId())
                .provider(config.getProvider())
                .endpoint(config.getEndpoint())
                .model(config.getModel())
                .auditRole(config.getAuditRole())
                .ticketEndpoint(config.getTicketEndpoint())
                .ticketToken(config.getTicketToken())
                .orderAuditEndpoint(config.getOrderAuditEndpoint())
                .orderHttpMethod(config.getOrderHttpMethod() == null ? "GET" : config.getOrderHttpMethod().toUpperCase())
                .orderListBody(config.getOrderListBody())
                .orderDetailBody(config.getOrderDetailBody())
                .hasApiKey(config.hasApiKey())
                .build();
    }

    private String normalizeHttpMethod(String method) {
        return "POST".equalsIgnoreCase(method.trim()) ? "POST" : "GET";
    }

    private String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    @Transactional(readOnly = true)
    public ApiConfig getRawApiConfig() {
        return apiConfigMapper.selectById(1L);
    }
}
