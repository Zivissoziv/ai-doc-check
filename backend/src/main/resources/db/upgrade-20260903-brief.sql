-- SmartDoc 变更简报功能升级脚本（针对已初始化的存量数据库执行）
-- 执行内容：
--   1. rule_group 表新增 group_type、brief_style 字段
--   2. rule 表新增 group_type 字段
--   3. 新建 order_brief_record 表
-- 存量数据默认 group_type = 'AUDIT'，行为与升级前完全一致。

USE smartdoc;

-- 1. rule_group 新增字段
ALTER TABLE rule_group
    ADD COLUMN group_type VARCHAR(20) NOT NULL DEFAULT 'AUDIT' COMMENT '规则组类型：AUDIT=审核规则组/BRIEF=变更简报总结规则组' AFTER group_name,
    ADD COLUMN brief_style TEXT DEFAULT NULL COMMENT '简报风格（仅BRIEF规则组使用，自由文本引导模型输出格式）' AFTER group_type;

ALTER TABLE rule_group ADD INDEX idx_group_type (group_type);

-- 2. rule 新增字段
ALTER TABLE rule
    ADD COLUMN group_type VARCHAR(20) NOT NULL DEFAULT 'AUDIT' COMMENT '规则类型：AUDIT=审核规则/BRIEF=变更简报总结规则' AFTER audit_scope;

-- 3. 变更简报总结记录表
CREATE TABLE IF NOT EXISTS order_brief_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    order_id VARCHAR(100) NOT NULL COMMENT '工单ID',
    ts VARCHAR(20) NOT NULL COMMENT '时间戳',
    rule_group_id BIGINT DEFAULT NULL COMMENT '使用的总结规则组ID',
    brief_batch_no VARCHAR(32) DEFAULT NULL COMMENT '总结批次号',
    document_name VARCHAR(200) DEFAULT NULL COMMENT '文档名称',
    task_id VARCHAR(36) DEFAULT NULL COMMENT '异步任务ID',
    status VARCHAR(20) DEFAULT NULL COMMENT '任务状态：PENDING/RUNNING/COMPLETED/FAILED',
    brief_content LONGTEXT DEFAULT NULL COMMENT 'AI简报内容（Markdown）',
    error_message VARCHAR(1000) DEFAULT NULL COMMENT '失败原因',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_brief_order_ts_created_at (order_id, ts, created_at),
    UNIQUE INDEX uk_brief_task_id (task_id),
    INDEX idx_brief_batch_no (brief_batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='变更简报总结记录表';
