-- SmartDoc 权限组功能升级脚本（针对已初始化的存量数据库执行）
-- 执行内容：
--   1. 新建 permission_group 表（权限组：控制变更简报入口可见性与规则组可见范围，通过 ?pgroup=xxx 切换）
-- 存量数据不受影响：不配置任何权限组时全部可见，行为与升级前一致。

USE smartdoc;

CREATE TABLE IF NOT EXISTS permission_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    perm_key VARCHAR(64) NOT NULL UNIQUE COMMENT 'URL参数值（?pgroup=xxx）',
    perm_name VARCHAR(100) NOT NULL COMMENT '权限组名称',
    brief_visible BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否可见变更简报入口',
    visible_group_ids TEXT DEFAULT NULL COMMENT '可见规则组ID列表(JSON数组)，NULL/空=全部可见',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_perm_key (perm_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='权限组表';
