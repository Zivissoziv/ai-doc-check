-- SmartDoc 数据库初始化脚本
SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;

CREATE DATABASE IF NOT EXISTS smartdoc DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

USE smartdoc;

SET CHARACTER SET utf8mb4;

CREATE TABLE IF NOT EXISTS rule_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    group_id VARCHAR(50) NOT NULL UNIQUE COMMENT '规则组标识',
    group_name VARCHAR(200) NOT NULL COMMENT '规则组名称',
    is_default BOOLEAN DEFAULT FALSE COMMENT '是否默认规则组',
    is_locked BOOLEAN DEFAULT FALSE COMMENT '是否锁定',
    lock_password VARCHAR(100) DEFAULT NULL COMMENT '锁定密码',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_group_group_id (group_id),
    INDEX idx_group_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='规则组表';

CREATE TABLE IF NOT EXISTS rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    rule_group_id BIGINT NOT NULL COMMENT '规则组ID',
    rule_name VARCHAR(200) NOT NULL COMMENT '规则名称',
    prompt VARCHAR(5000) NOT NULL COMMENT '审核提示词',
    severity VARCHAR(20) DEFAULT 'WARNING' COMMENT '严重级别：ERROR/WARNING/INFO',
    is_enabled BOOLEAN DEFAULT TRUE COMMENT '是否启用',
    sort_order INT DEFAULT 0 COMMENT '排序序号',
    trigger_condition VARCHAR(500) DEFAULT NULL COMMENT '触发条件',
    audit_scope VARCHAR(20) DEFAULT 'DOCUMENT' COMMENT '审核范围：DOCUMENT/TICKET',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (rule_group_id) REFERENCES rule_group(id) ON DELETE CASCADE,
    INDEX idx_rule_group_id (rule_group_id),
    INDEX idx_rule_group_scope (rule_group_id, audit_scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='规则表';

CREATE TABLE IF NOT EXISTS api_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    provider VARCHAR(50) DEFAULT 'custom' COMMENT '服务提供方',
    endpoint VARCHAR(500) DEFAULT NULL COMMENT 'AI API地址',
    api_key VARCHAR(500) DEFAULT NULL COMMENT 'AI API密钥',
    model VARCHAR(100) DEFAULT NULL COMMENT 'AI模型',
    audit_role VARCHAR(200) DEFAULT NULL COMMENT '审核角色',
    ticket_endpoint VARCHAR(500) DEFAULT NULL COMMENT '工单系统地址',
    ticket_token VARCHAR(500) DEFAULT NULL COMMENT '工单系统令牌',
    order_audit_endpoint VARCHAR(500) DEFAULT NULL COMMENT '工单审核数据接口地址',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='API配置表';

CREATE TABLE IF NOT EXISTS template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    template_name VARCHAR(200) NOT NULL COMMENT '模板名称',
    file_name VARCHAR(100) DEFAULT NULL COMMENT '文件名',
    description VARCHAR(500) DEFAULT NULL COMMENT '描述',
    is_default BOOLEAN DEFAULT FALSE COMMENT '是否默认模板',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='模板表';

CREATE TABLE IF NOT EXISTS audit_feedback (
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
    INDEX idx_feedback_rule_id (rule_id),
    INDEX idx_feedback_group_id (group_id),
    INDEX idx_feedback_batch_no (audit_batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='文档审核反馈表';

CREATE TABLE IF NOT EXISTS audit_ticket_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    ticket_id VARCHAR(100) NOT NULL COMMENT '工单ID',
    ts VARCHAR(20) NOT NULL COMMENT '时间戳',
    audit_batch_no VARCHAR(32) DEFAULT NULL COMMENT '审核批次号',
    document_name VARCHAR(200) DEFAULT NULL COMMENT '文档名称',
    task_id VARCHAR(36) DEFAULT NULL COMMENT '异步任务ID',
    status VARCHAR(20) DEFAULT NULL COMMENT '任务状态：PENDING/RUNNING/COMPLETED/FAILED',
    error_message VARCHAR(1000) DEFAULT NULL COMMENT '失败原因',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_ticket_ts_created_at (ticket_id, ts, created_at),
    UNIQUE INDEX uk_task_id (task_id),
    INDEX idx_audit_ticket_batch_no (audit_batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='工单审核记录表';

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
