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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderDataService {

    private final ObjectMapper objectMapper;

    public Map<String, Object> fetchOrderInfo(ApiConfig config, String orderId) throws Exception {
        String url = buildOrderUrl(config, orderId);
        log.info("Fetching order audit data: {}", url);
        JsonNode root = requestJson(url);

        // 兼容两种形态：单条对象 / 工单列表（在列表中按 orderId 定位）
        JsonNode listNode = extractListNode(root);
        if (listNode != null) {
            JsonNode matched = findOrderNode(listNode, orderId);
            if (matched == null) {
                throw new RuntimeException("Order not found in list response: " + orderId);
            }
            return toOrderResult(matched, orderId);
        }
        return toOrderResult(root, orderId);
    }

    private String buildOrderUrl(ApiConfig config, String orderId) {
        String url = config.getOrderAuditEndpoint().replace("{id}", orderId);
        if (!url.contains(orderId)) {
            url = url.replaceAll("\\{orderId}", orderId);
        }
        return url;
    }

    private JsonNode requestJson(String url) throws Exception {
        RestTemplate rt = createRestTemplate(15000);
        HttpHeaders headers = new HttpHeaders();
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = rt.exchange(url, HttpMethod.GET, entity, String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Order audit service returned: " + response.getStatusCode());
        }
        return objectMapper.readTree(response.getBody());
    }

    private Map<String, Object> toOrderResult(JsonNode node, String orderId) throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId);
        if (node.has("documentName")) {
            result.put("documentName", node.get("documentName").asText());
        }
        if (node.has("data") && node.get("data").isObject()) {
            result.put("data", objectMapper.convertValue(node.get("data"), Map.class));
        } else {
            result.put("data", objectMapper.convertValue(node, Map.class));
        }
        return result;
    }

    private JsonNode findOrderNode(JsonNode array, String orderId) {
        for (JsonNode item : array) {
            String id = firstText(item, "orderId", "order_id", "id", "orderNo", "order_no");
            if (orderId.equals(id)) {
                return item;
            }
        }
        return null;
    }

    /**
     * 变更简报：按时间范围搜索工单列表。
     * 复用 order_audit_endpoint 配置：
     * - 若 URL 中含 {start}/{end}（或 {startTime}/{endTime}）占位符则直接替换；
     * - 否则在 URL 后追加 startTime/endTime 查询参数。
     * 返回 order JSON list，每一项保留完整详情。
     */
    public List<Map<String, Object>> searchOrders(ApiConfig config, String startTime, String endTime) throws Exception {
        String url = config.getOrderAuditEndpoint();
        if (url.contains("{start}") || url.contains("{startTime}")) {
            url = url.replace("{startTime}", startTime).replace("{start}", startTime);
        }
        if (url.contains("{end}") || url.contains("{endTime}")) {
            url = url.replace("{endTime}", endTime).replace("{end}", endTime);
        } else {
            url = url + (url.contains("?") ? "&" : "?")
                    + "startTime=" + urlEncode(startTime)
                    + "&endTime=" + urlEncode(endTime);
        }

        log.info("Searching orders by time range: {} ~ {} -> {}", startTime, endTime, url);

        RestTemplate rt = createRestTemplate(15000);
        HttpHeaders headers = new HttpHeaders();
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = rt.exchange(url, HttpMethod.GET, entity, String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Order audit service returned: " + response.getStatusCode());
        }

        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode items = extractListNode(root);
        if (items == null || !items.isArray()) {
            throw new RuntimeException("Order search response is not a JSON list");
        }

        List<Map<String, Object>> orders = new ArrayList<>();
        for (JsonNode item : items) {
            Map<String, Object> order = new HashMap<>();
            order.put("orderId", firstText(item, "orderId", "order_id", "id", "orderNo", "order_no"));
            order.put("documentName", firstText(item, "documentName", "document_name", "name", "title"));
            order.put("data", objectMapper.convertValue(item, Map.class));
            orders.add(order);
        }
        log.info("Order search returned {} orders", orders.size());
        return orders;
    }

    private JsonNode extractListNode(JsonNode root) {
        if (root == null) {
            return null;
        }
        if (root.isArray()) {
            return root;
        }
        String[] candidates = {"data", "orders", "list", "records", "rows", "result"};
        for (String key : candidates) {
            JsonNode node = root.get(key);
            if (node != null && node.isArray()) {
                return node;
            }
        }
        return null;
    }

    private String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode v = node.get(key);
            if (v != null && !v.isNull() && !v.asText().trim().isEmpty()) {
                return v.asText();
            }
        }
        return null;
    }

    private String urlEncode(String value) {
        if (value == null) {
            return "";
        }
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private RestTemplate createRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }
}
