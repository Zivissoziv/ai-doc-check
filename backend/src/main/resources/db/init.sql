-- SmartDoc database initialization script.
SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;

CREATE DATABASE IF NOT EXISTS smartdoc DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

USE smartdoc;

SET CHARACTER SET utf8mb4;

CREATE TABLE IF NOT EXISTS rule_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Primary key',
    group_id VARCHAR(50) NOT NULL UNIQUE COMMENT 'Rule group identifier',
    group_name VARCHAR(200) NOT NULL COMMENT 'Rule group name',
    is_default BOOLEAN DEFAULT FALSE COMMENT 'Default rule group flag',
    is_locked BOOLEAN DEFAULT FALSE COMMENT 'Locked flag',
    lock_password VARCHAR(100) DEFAULT NULL COMMENT 'Lock password',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
    INDEX idx_group_group_id (group_id),
    INDEX idx_group_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Rule group table';

CREATE TABLE IF NOT EXISTS rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Primary key',
    rule_group_id BIGINT NOT NULL COMMENT 'Rule group ID',
    rule_name VARCHAR(200) NOT NULL COMMENT 'Rule name',
    prompt VARCHAR(5000) NOT NULL COMMENT 'Audit prompt',
    severity VARCHAR(20) DEFAULT 'WARNING' COMMENT 'Severity: ERROR/WARNING/INFO',
    is_enabled BOOLEAN DEFAULT TRUE COMMENT 'Enabled flag',
    sort_order INT DEFAULT 0 COMMENT 'Sort order',
    trigger_condition VARCHAR(500) DEFAULT NULL COMMENT 'Trigger condition',
    audit_scope VARCHAR(20) DEFAULT 'DOCUMENT' COMMENT 'Audit scope: DOCUMENT/TICKET',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
    FOREIGN KEY (rule_group_id) REFERENCES rule_group(id) ON DELETE CASCADE,
    INDEX idx_rule_group_id (rule_group_id),
    INDEX idx_rule_group_scope (rule_group_id, audit_scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Rule table';

CREATE TABLE IF NOT EXISTS api_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Primary key',
    provider VARCHAR(50) DEFAULT 'custom' COMMENT 'Provider',
    endpoint VARCHAR(500) DEFAULT NULL COMMENT 'AI API endpoint',
    api_key VARCHAR(500) DEFAULT NULL COMMENT 'AI API key',
    model VARCHAR(100) DEFAULT NULL COMMENT 'AI model',
    audit_role VARCHAR(200) DEFAULT NULL COMMENT 'Audit role',
    ticket_endpoint VARCHAR(500) DEFAULT NULL COMMENT 'Ticket system endpoint',
    ticket_token VARCHAR(500) DEFAULT NULL COMMENT 'Ticket system token',
    order_audit_endpoint VARCHAR(500) DEFAULT NULL COMMENT 'Order audit data endpoint',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='API config table';

CREATE TABLE IF NOT EXISTS template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Primary key',
    template_name VARCHAR(200) NOT NULL COMMENT 'Template name',
    file_name VARCHAR(100) DEFAULT NULL COMMENT 'File name',
    description VARCHAR(500) DEFAULT NULL COMMENT 'Description',
    is_default BOOLEAN DEFAULT FALSE COMMENT 'Default template flag',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Template table';

CREATE TABLE IF NOT EXISTS audit_feedback (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Primary key',
    rule_id BIGINT DEFAULT NULL COMMENT 'Rule ID',
    group_id VARCHAR(50) DEFAULT NULL COMMENT 'Rule group ID',
    audit_batch_no VARCHAR(32) DEFAULT NULL COMMENT 'Audit batch number',
    pass BOOLEAN DEFAULT NULL COMMENT 'Audit pass flag',
    confidence INT DEFAULT NULL COMMENT 'Confidence',
    results_json VARCHAR(10000) DEFAULT NULL COMMENT 'Audit result JSON',
    feedback_type VARCHAR(20) DEFAULT NULL COMMENT 'Feedback type: ACCURATE/INACCURATE',
    reason VARCHAR(500) DEFAULT NULL COMMENT 'Feedback reason',
    duration_ms BIGINT DEFAULT NULL COMMENT 'Audit duration in ms',
    skipped TINYINT(1) DEFAULT 0 COMMENT 'Skipped because data was missing',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
    INDEX idx_feedback_rule_id (rule_id),
    INDEX idx_feedback_group_id (group_id),
    INDEX idx_feedback_batch_no (audit_batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Document audit feedback table';

CREATE TABLE IF NOT EXISTS audit_ticket_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Primary key',
    ticket_id VARCHAR(100) NOT NULL COMMENT 'Ticket ID',
    ts VARCHAR(20) NOT NULL COMMENT 'Timestamp',
    audit_batch_no VARCHAR(32) DEFAULT NULL COMMENT 'Audit batch number',
    document_name VARCHAR(200) DEFAULT NULL COMMENT 'Document name',
    task_id VARCHAR(36) DEFAULT NULL COMMENT 'Async task ID',
    status VARCHAR(20) DEFAULT NULL COMMENT 'Task status: PENDING/RUNNING/COMPLETED/FAILED',
    error_message VARCHAR(1000) DEFAULT NULL COMMENT 'Failure reason',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
    INDEX idx_ticket_ts_created_at (ticket_id, ts, created_at),
    UNIQUE INDEX uk_task_id (task_id),
    INDEX idx_audit_ticket_batch_no (audit_batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Ticket audit record table';

CREATE TABLE IF NOT EXISTS audit_order_feedback (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    rule_id BIGINT DEFAULT NULL COMMENT '规则ID',
    group_id VARCHAR(50) DEFAULT NULL COMMENT '规则组ID',
    audit_batch_no VARCHAR(32) DEFAULT NULL COMMENT '审核批次号',
    pass BOOLEAN DEFAULT NULL COMMENT '审核是否通过',
    confidence INT DEFAULT NULL COMMENT '置信度',
    results_json VARCHAR(10000) DEFAULT NULL COMMENT '审核结果JSON',
    feedback_type VARCHAR(20) DEFAULT NULL COMMENT '反馈类型：ACCURATE/INACCURATE',
    reason VARCHAR(500) DEFAULT NULL COMMENT '反馈原因',
    duration_ms BIGINT DEFAULT NULL COMMENT '审核耗时，单位毫秒',
    skipped TINYINT(1) DEFAULT 0 COMMENT '是否因数据缺失跳过',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_order_feedback_rule_id (rule_id),
    INDEX idx_order_feedback_group_id (group_id),
    INDEX idx_order_feedback_batch_no (audit_batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='工单审核反馈表';

CREATE TABLE IF NOT EXISTS audit_order_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    order_id VARCHAR(100) NOT NULL COMMENT '工单ID',
    ts VARCHAR(20) NOT NULL COMMENT '时间戳',
    audit_batch_no VARCHAR(32) DEFAULT NULL COMMENT '审核批次号',
    document_name VARCHAR(200) DEFAULT NULL COMMENT '文档名称',
    task_id VARCHAR(36) DEFAULT NULL COMMENT '异步任务ID',
    status VARCHAR(20) DEFAULT NULL COMMENT '任务状态：PENDING/RUNNING/COMPLETED/FAILED',
    error_message VARCHAR(1000) DEFAULT NULL COMMENT '失败原因',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_order_ts_created_at (order_id, ts, created_at),
    UNIQUE INDEX uk_order_task_id (task_id),
    INDEX idx_audit_order_batch_no (audit_batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='工单审核记录表';
