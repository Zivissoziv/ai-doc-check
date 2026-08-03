package com.smartdoc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdoc.entity.ApiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderDataService {

    private final ObjectMapper objectMapper;

    public Map<String, Object> fetchOrderInfo(ApiConfig config, String orderId) throws Exception {
        String url = config.getOrderAuditEndpoint().replace("{id}", orderId);
        if (!url.contains(orderId)) {
            url = url.replaceAll("\\{orderId}", orderId);
        }

        log.info("Fetching order audit data: {}", url);

        RestTemplate rt = createRestTemplate(15000);
        HttpHeaders headers = new HttpHeaders();
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = rt.exchange(url, HttpMethod.GET, entity, String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Order audit service returned: " + response.getStatusCode());
        }

        JsonNode root = objectMapper.readTree(response.getBody());
        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId);
        if (root.has("documentName")) {
            result.put("documentName", root.get("documentName").asText());
        }
        if (root.has("data")) {
            result.put("data", objectMapper.convertValue(root.get("data"), Map.class));
        } else {
            result.put("data", objectMapper.convertValue(root, Map.class));
        }
        return result;
    }

    private RestTemplate createRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }
}
