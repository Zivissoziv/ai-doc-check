package com.smartdoc.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.Size;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiConfigDto {

    private Long id;

    @Size(max = 50)
    private String provider;

    @Size(max = 500)
    private String endpoint;

    private String apiKey;

    @Size(max = 100)
    private String model;

    @Size(max = 100)
    private String auditRole;

    @Size(max = 500)
    private String ticketEndpoint;

    private String ticketToken;

    @Size(max = 500)
    private String orderAuditEndpoint;

    /** 工单接口请求方法：GET / POST */
    private String orderHttpMethod;

    /** 列表请求体模板（POST 用） */
    private String orderListBody;

    /** 单条工单请求体模板（POST 用），为空则复用列表模板 */
    private String orderDetailBody;

    private Boolean hasApiKey;
}
