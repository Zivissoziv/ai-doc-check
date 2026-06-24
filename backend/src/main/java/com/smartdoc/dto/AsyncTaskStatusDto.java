package com.smartdoc.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AsyncTaskStatusDto {

    private String taskId;

    private String status;

    private String errorMessage;

    /** 审核完成后的批次号，status=COMPLETED 时有值 */
    private String auditBatchNo;
}
