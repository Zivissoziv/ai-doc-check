package com.smartdoc.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdoc.entity.ApiConfig;
import com.smartdoc.service.ApiConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/ticket")
@RequiredArgsConstructor
public class TicketController {

    private final ApiConfigService apiConfigService;
    private final ObjectMapper objectMapper;

    @GetMapping("/{ticketId}")
    public ResponseEntity<Map<String, Object>> getTicket(@PathVariable String ticketId) {
        ApiConfig config = apiConfigService.getRawApiConfig();
        if (config == null || config.getTicketEndpoint() == null || config.getTicketEndpoint().isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(errorMap("工单服务未配置，请在API配置中设置 ticketEndpoint"));
        }

        String url = config.getTicketEndpoint().replace("{id}", ticketId);
        if (!url.contains(ticketId)) {
            url = url.replaceAll("\\{ticketId}", ticketId);
        }

        log.info("获取工单信息: {}", url);

        try {
            RestTemplate rt = createRestTemplate(15000);
            HttpHeaders headers = new HttpHeaders();
            if (config.getTicketToken() != null && !config.getTicketToken().isEmpty()) {
                headers.set("Authorization", "Bearer " + config.getTicketToken());
            }
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = rt.exchange(url, HttpMethod.GET, entity, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(errorMap("工单系统返回异常: " + response.getStatusCode()));
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            Map<String, Object> result = new HashMap<>();
            result.put("ticketId", ticketId);

            if (root.has("documentUrl")) {
                result.put("documentUrl", root.get("documentUrl").asText());
            }
            if (root.has("documentBase64")) {
                result.put("documentBase64", root.get("documentBase64").asText());
            }
            if (root.has("documentName")) {
                result.put("documentName", root.get("documentName").asText());
            }
            if (root.has("data")) {
                result.put("data", objectMapper.convertValue(root.get("data"), Map.class));
            }
            if (root.has("templateDocUrl")) {
                result.put("templateDocUrl", root.get("templateDocUrl").asText());
            }
            if (root.has("templateBase64")) {
                result.put("templateBase64", root.get("templateBase64").asText());
            }
            if (root.has("templateDocName")) {
                result.put("templateDocName", root.get("templateDocName").asText());
            }

            log.info("工单 {} 加载成功", ticketId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("获取工单失败: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(errorMap("获取工单失败: " + e.getMessage()));
        }
    }

    @PostMapping("/download")
    public ResponseEntity<byte[]> downloadFile(@RequestBody Map<String, String> body) {
        String url = body.get("url");
        if (url == null || url.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        String lowerUrl = url.toLowerCase();
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
            return ResponseEntity.badRequest().build();
        }

        log.info("代理下载文件: {}", url);
        try {
            RestTemplate rt = createRestTemplate(60000);
            ResponseEntity<byte[]> response = rt.getForEntity(url, byte[].class);
            byte[] fileBytes = response.getBody();
            if (fileBytes == null) {
                return ResponseEntity.notFound().build();
            }

            HttpHeaders headers = new HttpHeaders();
            MediaType contentType = response.getHeaders().getContentType();
            headers.setContentType(contentType != null ? contentType : MediaType.APPLICATION_OCTET_STREAM);

            return new ResponseEntity<>(fileBytes, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("代理下载失败: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    private RestTemplate createRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }

    private Map<String, Object> errorMap(String message) {
        Map<String, Object> map = new HashMap<>();
        map.put("error", message);
        return map;
    }
}
