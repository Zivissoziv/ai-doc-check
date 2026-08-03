package com.smartdoc.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AsyncOrderTaskStatusDto {

    private String taskId;

    private String orderId;

    private String ts;

    private String status;

    private String errorMessage;

    private String auditBatchNo;

    private String documentName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
