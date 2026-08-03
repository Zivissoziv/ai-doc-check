USE smartdoc;

SET @col := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'api_config'
      AND column_name = 'order_audit_endpoint'
);
SET @sql := IF(@col = 0,
    'ALTER TABLE api_config ADD COLUMN order_audit_endpoint VARCHAR(500) DEFAULT NULL COMMENT ''Order audit data endpoint'' AFTER ticket_token',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS audit_order_feedback (
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
    INDEX idx_order_feedback_rule_id (rule_id),
    INDEX idx_order_feedback_group_id (group_id),
    INDEX idx_order_feedback_batch_no (audit_batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Order audit feedback table';

CREATE TABLE IF NOT EXISTS audit_order_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Primary key',
    order_id VARCHAR(100) NOT NULL COMMENT 'Order ID',
    ts VARCHAR(20) NOT NULL COMMENT 'Timestamp',
    audit_batch_no VARCHAR(32) DEFAULT NULL COMMENT 'Audit batch number',
    document_name VARCHAR(200) DEFAULT NULL COMMENT 'Document name',
    task_id VARCHAR(36) DEFAULT NULL COMMENT 'Async task ID',
    status VARCHAR(20) DEFAULT NULL COMMENT 'Task status: PENDING/RUNNING/COMPLETED/FAILED',
    error_message VARCHAR(1000) DEFAULT NULL COMMENT 'Failure reason',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
    INDEX idx_order_ts_created_at (order_id, ts, created_at),
    UNIQUE INDEX uk_order_task_id (task_id),
    INDEX idx_audit_order_batch_no (audit_batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='Order audit record table';
