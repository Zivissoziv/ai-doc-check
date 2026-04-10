package com.smartdoc.dto;

import lombok.*;

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

    private Boolean hasApiKey;
}