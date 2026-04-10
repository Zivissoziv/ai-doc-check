package com.smartdoc.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditIssueDto {

    private String location;

    private String problem;

    private String suggestion;
}