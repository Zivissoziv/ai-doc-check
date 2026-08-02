-- Split rules inside the same rule group by audit target.
-- Existing rules are treated as document-audit rules for backward compatibility.

USE smartdoc;

SET @col := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'rule'
      AND column_name = 'audit_scope'
);
SET @sql := IF(@col = 0,
    'ALTER TABLE rule ADD COLUMN audit_scope VARCHAR(20) DEFAULT ''DOCUMENT'' COMMENT ''规则作用域: DOCUMENT/TICKET'' AFTER trigger_condition',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE rule SET audit_scope = 'DOCUMENT' WHERE audit_scope IS NULL OR audit_scope = '';

SET @idx := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'rule'
      AND index_name = 'idx_rule_group_scope'
);
SET @sql := IF(@idx = 0,
    'CREATE INDEX idx_rule_group_scope ON rule(rule_group_id, audit_scope)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
