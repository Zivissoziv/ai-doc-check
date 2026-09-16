-- SmartDoc 工单接口支持 POST 升级脚本（针对已初始化的存量数据库执行）
-- 执行内容：
--   1. api_config 新增 order_http_method（GET/POST）
--   2. api_config 新增 order_list_body（列表请求体模板，POST 用）
--   3. api_config 新增 order_detail_body（单条详情请求体模板，POST 用）
-- 存量数据默认 order_http_method='GET'，行为与升级前完全一致。

USE smartdoc;

ALTER TABLE api_config
    ADD COLUMN order_http_method VARCHAR(10) NOT NULL DEFAULT 'GET' COMMENT '工单接口请求方法：GET/POST' AFTER order_audit_endpoint,
    ADD COLUMN order_list_body TEXT DEFAULT NULL COMMENT '列表请求体模板(JSON)，支持 {startTime}/{start}/{endTime}/{end} 占位符' AFTER order_http_method,
    ADD COLUMN order_detail_body TEXT DEFAULT NULL COMMENT '单条工单请求体模板(JSON)，支持 {id}/{orderId} 占位符；为空则复用列表模板' AFTER order_list_body;
