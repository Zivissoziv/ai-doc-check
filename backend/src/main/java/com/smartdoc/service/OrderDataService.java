package com.smartdoc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.smartdoc.entity.ApiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderDataService {

    private final ObjectMapper objectMapper;

    /**
     * 取单条工单详情。
     * - 若配置地址里带 {id}/{orderId} 占位符：直接请求该地址（旧行为，保持兼容）；
     * - 否则：走同一个列表接口（详情由列表接口返回），在返回列表中按 orderId 匹配。
     */
    public Map<String, Object> fetchOrderInfo(ApiConfig config, String orderId) throws Exception {
        String endpoint = config.getOrderAuditEndpoint();
        boolean dedicatedDetailUrl = endpoint != null
                && (endpoint.contains("{id}") || endpoint.contains("{orderId}"));

        // 详情请求体：优先 order_detail_body；为空时若列表模板里含 {id}/{orderId} 则复用，否则用默认模板
        String detailTemplate = config.getOrderDetailBody();
        if (isBlank(detailTemplate)) {
            String listBody = config.getOrderListBody();
            detailTemplate = (!isBlank(listBody) && (listBody.contains("{id}") || listBody.contains("{orderId}")))
                    ? listBody : null;
        }

        String url;
        if (dedicatedDetailUrl) {
            // 注意：不要用 Map.of()，它属于 Java 9+ API，本工程编译目标为 1.8
            Map<String, String> detailVars = new HashMap<>();
            detailVars.put("id", orderId);
            detailVars.put("orderId", orderId);
            url = applyPlaceholders(endpoint, detailVars);
            log.info("Fetching order detail via dedicated url: {}", url);
        } else {
            url = buildListUrl(config, null, null);
            log.info("Fetching order detail via list api: {}, orderId={}", url, orderId);
        }

        JsonNode root = callOrderApi(config, url,
                buildRequestBody(config, detailTemplate, true, orderId, null, null));

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

    private boolean isPost(ApiConfig config) {
        return config != null && "POST".equalsIgnoreCase(
                config.getOrderHttpMethod() == null ? "GET" : config.getOrderHttpMethod());
    }

    /**
     * 统一的工单接口调用：GET 不带请求体，POST 带 JSON 请求体。
     */
    private JsonNode callOrderApi(ApiConfig config, String url, String body) throws Exception {
        RestTemplate rt = createRestTemplate(15000);
        HttpHeaders headers = new HttpHeaders();
        HttpEntity<?> entity;
        HttpMethod method;
        if (isPost(config)) {
            headers.setContentType(MediaType.APPLICATION_JSON);
            entity = new HttpEntity<>(body == null || body.trim().isEmpty() ? "{}" : body, headers);
            method = HttpMethod.POST;
        } else {
            entity = new HttpEntity<>(headers);
            method = HttpMethod.GET;
        }

        // 以字节接收，自行决定解码字符集：上游 Content-Type 常不带 charset，
        // RestTemplate 的 StringHttpMessageConverter 此时默认按 ISO-8859-1 解码，中文会变乱码
        ResponseEntity<byte[]> response = rt.exchange(url, method, entity, byte[].class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Order audit service returned: " + response.getStatusCode());
        }
        String bodyText = decodeBody(response.getHeaders().getContentType(), response.getBody());
        return objectMapper.readTree(bodyText);
    }

    /**
     * 响应体解码：优先用响应头声明的 charset；未声明时先严格校验 UTF-8，
     * 校验失败（非法 UTF-8 序列）再按 GBK 兜底，兼容老系统的中文编码。
     */
    private String decodeBody(MediaType contentType, byte[] bytes) {
        if (contentType != null && contentType.getCharset() != null) {
            return new String(bytes, contentType.getCharset());
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            log.warn("Order response is not valid UTF-8, falling back to GBK decoding");
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    /**
     * 组装 POST 请求体：模板为空时按用途给默认模板；占位符会被实际值替换（值做 JSON 转义）。
     */
    private String buildRequestBody(ApiConfig config, String template, boolean detail,
                                    String orderId, String startTime, String endTime) {
        if (!isPost(config)) {
            return null;
        }
        String tpl = template;
        if (isBlank(tpl)) {
            tpl = detail
                    ? "{\"orderId\": \"{id}\"}"
                    : "{\"startTime\": \"{startTime}\", \"endTime\": \"{endTime}\"}";
        }
        Map<String, String> vars = new HashMap<>();
        String id = escapeJsonValue(nullToEmpty(orderId));
        String start = escapeJsonValue(nullToEmpty(startTime));
        String end = escapeJsonValue(nullToEmpty(endTime));
        vars.put("id", id);
        vars.put("orderId", id);
        vars.put("startTime", start);
        vars.put("start", start);
        vars.put("endTime", end);
        vars.put("end", end);
        return applyPlaceholders(tpl, vars);
    }

    private String applyPlaceholders(String template, Map<String, String> vars) {
        String result = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private String escapeJsonValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private Map<String, Object> toOrderResult(JsonNode node, String orderId) throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("orderId", firstText(node, ORDER_ID_KEYS) != null
                ? firstText(node, ORDER_ID_KEYS) : orderId);
        String name = firstText(node, ORDER_NAME_KEYS);
        if (name != null) {
            result.put("documentName", name);
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
            String id = firstText(item, ORDER_ID_KEYS);
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
        return (List<Map<String, Object>>) searchOrdersWithMeta(config, startTime, endTime).get("orders");
    }

    /**
     * 与 {@link #searchOrders} 相同，但额外透出上游返回的总条数（形如 {"total":"145"} 的分页字段），
     * 便于前端提示「上游共 N 条，本次返回 M 条」。
     */
    public Map<String, Object> searchOrdersWithMeta(ApiConfig config, String startTime, String endTime) throws Exception {
        String url = buildListUrl(config, startTime, endTime);
        String body = buildRequestBody(config, config.getOrderListBody(), false, null, startTime, endTime);
        log.info("Searching orders by time range: {} ~ {} -> {} ({}), body={}",
                startTime, endTime, url, isPost(config) ? "POST" : "GET", body);

        JsonNode root = callOrderApi(config, url, body);
        JsonNode items = extractListNode(root);
        if (items == null || !items.isArray()) {
            throw new RuntimeException("Order search response is not a JSON list");
        }

        List<Map<String, Object>> orders = new ArrayList<>();
        for (JsonNode item : items) {
            Map<String, Object> order = new HashMap<>();
            order.put("orderId", firstText(item, ORDER_ID_KEYS));
            order.put("documentName", firstText(item, ORDER_NAME_KEYS));
            order.put("data", objectMapper.convertValue(item, Map.class));
            orders.add(order);
        }
        // 分层取上游的分页总条数（如 {"total":"145"}，可能在包裹对象里）
        Integer upstreamTotal = readUpstreamTotal(root, 0);

        log.info("Order search returned {} orders (upstream total={})", orders.size(), upstreamTotal);
        Map<String, Object> result = new HashMap<>();
        result.put("orders", orders);
        result.put("upstreamTotal", upstreamTotal);
        return result;
    }

    /**
     * 读上游返回里的总条数字段（total/totalCount/count…），支持嵌在包裹对象里，取不到返回 null。
     */
    private Integer readUpstreamTotal(JsonNode node, int depth) {
        if (node == null || depth > 4) {
            return null;
        }
        if (node.isObject()) {
            for (String key : TOTAL_KEYS) {
                JsonNode value = node.get(key);
                if (value != null && value.isValueNode() && !value.asText().trim().isEmpty()) {
                    try {
                        return Integer.valueOf(value.asText().trim());
                    } catch (NumberFormatException ignored) {
                        // 不是数字（如 message 字段）则继续找
                    }
                }
            }
            for (JsonNode child : node) {
                Integer found = readUpstreamTotal(child, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                Integer found = readUpstreamTotal(child, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 组装列表接口 URL：
     * - URL 里的 {start}/{startTime}、{end}/{endTime} 占位符会被替换（GET/POST 通用）；
     * - GET 且地址没有时间占位符时，追加 startTime/endTime 查询参数；POST 走请求体，不追加。
     */
    private String buildListUrl(ApiConfig config, String startTime, String endTime) {
        String url = config.getOrderAuditEndpoint();
        boolean hasStart = !isBlank(startTime);
        boolean hasEnd = !isBlank(endTime);

        // 注意：这里不要自行 URLEncoder，否则 RestTemplate 会再编码一次（%3A → %253A），
        // 值由 RestTemplate 统一编码
        if (url.contains("{start}") || url.contains("{startTime}")) {
            url = url.replace("{startTime}", hasStart ? startTime : "")
                    .replace("{start}", hasStart ? startTime : "");
        }
        if (url.contains("{end}") || url.contains("{endTime}")) {
            url = url.replace("{endTime}", hasEnd ? endTime : "")
                    .replace("{end}", hasEnd ? endTime : "");
        }

        boolean hasTimePlaceholder = url.contains("{start}") || url.contains("{startTime}")
                || url.contains("{end}") || url.contains("{endTime}");
        if (!isPost(config) && !hasTimePlaceholder && (hasStart || hasEnd)) {
            url = url + (url.contains("?") ? "&" : "?")
                    + "startTime=" + startTime
                    + "&endTime=" + endTime;
        }
        return url;
    }

    /**
     * 工单 ID 字段别名（按优先级）。`cchrreleaseid` 为变更管理系统实际使用的字段名。
     */
    private static final String[] ORDER_ID_KEYS = {
            "orderId", "order_id", "cchrreleaseid", "cchrReleaseid", "releaseId",
            "orderNo", "order_no", "id"
    };

    /**
     * 工单名称/标题字段别名（按优先级）。`applicationsystem` 为变更管理系统实际使用的字段名。
     */
    private static final String[] ORDER_NAME_KEYS = {
            "documentName", "document_name", "applicationsystem", "applicationSystem",
            "application_system", "name", "title"
    };

    /**
     * 常见「列表字段」键名，按优先级排列（用于在外层包裹结构中定位工单数组）。
     */
    private static final String[] LIST_KEYS = {
            "data", "datas", "rows", "records", "list", "lists", "orderList", "orders",
            "items", "result", "results", "content", "contentList", "body", "payload"
    };

    /**
     * 分页/包裹对象里常见的统计字段，用于判断「这个对象是包裹体而不是一条工单」。
     */
    private static final String[] PAGING_KEYS = {
            "total", "totals", "totalCount", "totalNum", "count", "pageNum", "pageNo",
            "pageSize", "pages", "pageCount", "code", "msg", "message", "success"
    };

    /**
     * 上游总条数字段（只认真正的计数字段，不含 code/msg 之类的状态字段）。
     */
    private static final String[] TOTAL_KEYS = {
            "total", "totals", "totalCount", "totalNum", "totalRows", "recordCount", "count"
    };

    /**
     * 从响应里提取工单数组。兼容多种形态：
     * <pre>
     *   [ {...}, {...} ]                                  // 直接是数组
     *   { "data": [ ... ] }                              // 包一层
     *   { "datas": [ { "total": "145", "rows": [ ... ] } ] }  // 包一层 + 分页包裹对象（需再下钻）
     *   { "data": { "records": [ ... ] } }               // 键值是对象，再取里面的数组
     * </pre>
     * 做法：限深广度优先找到第一个数组，再按需逐层解包「分页包裹对象」。
     */
    private JsonNode extractListNode(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode array = findFirstArray(root, 0);
        return array == null ? null : unwrapPagedArray(array);
    }

    /**
     * 限深查找第一个数组：优先匹配常见列表键名，其次递归进入同为列表键名的对象子节点。
     */
    private JsonNode findFirstArray(JsonNode node, int depth) {
        if (node == null || depth > 4) {
            return null;
        }
        if (node.isArray()) {
            return node;
        }
        if (!node.isObject()) {
            return null;
        }
        for (String key : LIST_KEYS) {
            JsonNode child = node.get(key);
            if (child != null && child.isArray()) {
                return child;
            }
        }
        for (String key : LIST_KEYS) {
            JsonNode child = node.get(key);
            if (child != null && child.isObject()) {
                JsonNode found = findFirstArray(child, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 解包分页包裹结构，如 {@code [ { "total": "145", "rows": [ ... ] } ]} → rows 数组。
     * 若外层数组的元素全部是分页包裹体，则把各自内层数组的结果合并返回。
     */
    private JsonNode unwrapPagedArray(JsonNode array) {
        if (array == null || !array.isArray() || array.size() == 0) {
            return array;
        }
        for (JsonNode element : array) {
            if (!isPagingWrapper(element)) {
                return array;
            }
        }

        ArrayNode merged = objectMapper.createArrayNode();
        for (JsonNode element : array) {
            JsonNode unwrapped = unwrapPagedArray(firstArrayChild(element));
            if (unwrapped != null && unwrapped.isArray()) {
                merged.addAll((ArrayNode) unwrapped);
            }
        }
        return merged;
    }

    /**
     * 判断一个对象是不是「分页包裹体」——即它自身不是一条工单，只是包着工单数组。
     * 判定：含子数组 + 不像工单 + （有分页字段 或 所有键都是列表键）。
     */
    private boolean isPagingWrapper(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        if (looksLikeOrder(node) || firstArrayChild(node) == null) {
            return false;
        }
        if (hasPagingKey(node)) {
            return true;
        }
        java.util.Iterator<String> names = node.fieldNames();
        boolean hasAny = false;
        while (names.hasNext()) {
            hasAny = true;
            if (!isListKey(names.next())) {
                return false;
            }
        }
        return hasAny;
    }

    private boolean isListKey(String key) {
        for (String candidate : LIST_KEYS) {
            if (candidate.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断一个对象是否「像一条工单」——含工单 ID 或名称类字段。
     * 用于避免把真实的工单对象误当成分页包裹体拆开；同时避免把
     * {@code {"name":"...","total":N,"rows":[]}} 这类包裹体误判成工单。
     */
    private boolean looksLikeOrder(JsonNode node) {
        // 有工单 ID：强信号，直接认定是工单
        if (firstText(node, ORDER_ID_KEYS) != null) {
            return true;
        }
        // 同时具备「分页字段 + 子数组」：更可能是包裹体，而不是只有名称字段的工单
        if (firstArrayChild(node) != null && hasPagingKey(node)) {
            return false;
        }
        return firstText(node, ORDER_NAME_KEYS) != null;
    }

    private boolean hasPagingKey(JsonNode node) {
        for (String key : PAGING_KEYS) {
            if (node.has(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取对象里的第一个数组子节点（优先常见列表键名，否则任意数组子节点）。
     */
    private JsonNode firstArrayChild(JsonNode node) {
        for (String key : LIST_KEYS) {
            JsonNode child = node.get(key);
            if (child != null && child.isArray()) {
                return child;
            }
        }
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            JsonNode value = fields.next().getValue();
            if (value.isArray()) {
                return value;
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

    private RestTemplate createRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }
}
