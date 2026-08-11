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
