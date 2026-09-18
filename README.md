# SmartDoc AI - 智能文档审核系统

基于 Spring Boot + 原生前端实现的智能文档审核工具，支持文档结构对比、AI 内容审核和统计分析。

![主界面概览](screenshots/main-interface.png)

## 功能特性

### 文档结构对比
上传模板文档与待审文档，自动提取章节结构并进行智能匹配对比。

- 支持模糊匹配和精确匹配两种模式
- 精确匹配下可对比章节内容相似度
- 结构差异一目了然，快速定位缺失或多余章节

![结构对比界面](screenshots/structure-compare.png)

### AI 内容审核
基于自定义规则，AI 智能分析文档内容并给出修改建议。

- 支持多套规则组管理
- 自然语言描述审核规则
- Excel 数据源动态注入
- 批量审核与结果导出

![AI审核结果界面](screenshots/audit-results.png)

### 变更简报
对接变更管理系统工单接口，按时间范围搜索工单并生成 AI 变更简报。

- 工单搜索：支持 GET/POST 可配置，兼容多层嵌套与分页包裹的响应结构
- 工单 ID / 名称字段内置内网变更系统别名（`cchrreleased` / `applicationsystem`）
- 全程仅调用一次工单接口，搜索结果整份直传后端生成简报
- 规则训练：粘贴训练材料总结规则语义，候选规则可应用到简报规则组
- 训练提示词可在界面调整（「提示词调整」按钮），按审核范围分别保存，支持一键恢复默认
- 支持默认规则组叠加审核（文档审核模式）

### 统计分析
全局调用统计，按规则组/规则筛选不准确反馈详情及原因。

![统计分析弹窗](screenshots/stats-analysis.png)


## 项目结构

```
ai-doc-check/
├── backend/                      # Spring Boot 后端服务
│   ├── src/main/java/            # Java 源码
│   │   └── com/smartdoc/
│   │       ├── config/           # 配置类（异步、加密、Web、MyBatis-Plus）
│   │       ├── controller/       # 控制器（审核、反馈、模板、规则组等）
│   │       ├── dto/              # 数据传输对象
│   │       ├── entity/           # 实体类
│   │       ├── exception/        # 全局异常处理
│   │       ├── mapper/           # MyBatis-Plus 映射
│   │       ├── service/          # 业务逻辑层
│   │       └── template/         # 模板管理
│   ├── src/main/resources/       # 配置文件与静态资源
│   │   ├── application.yml       # 主配置
│   │   ├── application-dev.yml   # 开发环境配置
│   │   ├── application-prod.yml  # 生产环境配置
│   │   ├── db/                   # SQL 脚本
│   │   └── prompts/              # AI 提示词模板
│   └── pom.xml                   # Maven 配置
├── frontend/                     # 前端静态文件
│   ├── js/                       # JavaScript 模块
│   │   ├── api-client.js         # API 请求封装
│   │   └── ui-helpers.js         # UI 工具函数
│   ├── libs/                     # 第三方库
│   ├── app.js                    # 主应用逻辑
│   ├── index.html                # 入口页面
│   └── styles.css                # 样式文件
└── README.md                     # 本文件
```

## 技术栈

### 后端
| 组件 | 版本 |
|------|------|
| JDK | 1.8 |
| Spring Boot | 2.7.18 |
| MyBatis-Plus | 3.5.3.1 |
| MySQL | 8.0+ |
| Apache POI | 5.2.3（Word 文档解析） |
| Apache PDFBox | 2.0.29（PDF 解析） |
| Apache Tika | 2.9.1（文档解析） |

### 前端
| 组件 | 用途 |
|------|------|
| 原生 JavaScript | 无框架依赖 |
| Tailwind CSS | 样式框架 |
| Mammoth.js | 浏览器端 Word 解析 |
| PDF.js | 浏览器端 PDF 解析 |
| SheetJS | Excel 数据导入 |

## 快速开始

### 1. 环境准备

- JDK 1.8+
- Maven 3.x
- MySQL 8.0+

### 2. 数据库初始化

```sql
CREATE DATABASE smartdoc DEFAULT CHARACTER SET utf8mb4;
USE smartdoc;
source backend/src/main/resources/db/init.sql;
```

### 3. 配置数据库连接

编辑 `backend/src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/smartdoc?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai
    username: your_username
    password: your_password
```

### 4. 构建与运行

**开发环境:**
```bash
cd backend
mvn clean install -DskipTests
mvn spring-boot:run
```

**生产环境:**
```bash
cd backend
mvn clean package -DskipTests

# Linux 启动
chmod +x start.sh
./start.sh

# Windows 启动
start.bat
```

服务启动后访问: `http://localhost:8080`

## 使用指南

### 配置 API
点击右上角「API设置」，填写 API 密钥和端点地址。

### 上传文档
1. 点击「选择模板」上传模板文档（可选）
2. 点击「上传待审文档」选择要审核的文件
3. 支持的格式：DOC、DOCX、PDF、TXT、MD

### 运行审核
1. 在右侧面板添加审核规则（自然语言描述）
2. 可选择上传 Excel 数据源动态注入数据
3. 点击「运行AI审核」开始智能审核

## 支持的相似度算法

### 标题匹配
- Jaccard 字符集相似度
- 层级匹配加权（+0.2）
- 关键词提取匹配（如"第一章"）

### 内容相似度
- 中文：`Jaccard × 0.8 + 长度比例 × 0.2 + 公共前缀奖励`
- 英文：单词级 Jaccard 相似度

## API 接口

路由前缀与后端 Controller 一一对应（下表与代码核对过，改接口时请同步更新）。

### 规则组与提示词（`RuleGroupController` / `PromptOverrideController` / `ApiConfigController`）

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/config/rules` | 获取所有规则组（可按 `groupType` 过滤） |
| GET | `/api/config/rules/{groupId}` | 获取指定规则组（含规则） |
| POST | `/api/config/rules` | 创建规则组 |
| PUT | `/api/config/rules/{groupId}` | 更新规则组（`?auditMode=`） |
| DELETE | `/api/config/rules/{groupId}` | 删除规则组 |
| POST | `/api/config/rules/{groupId}/lock` | 上锁规则组 |
| POST | `/api/config/rules/{groupId}/unlock` | 解锁规则组 |
| GET | `/api/config/rules/{groupId}/locked` | 查询规则组是否上锁 |
| PUT | `/api/config/rules/{groupId}/default` | 设为默认规则组（同类型互斥） |
| POST | `/api/config/rules/train` | 规则训练（从材料提炼候选规则） |
| GET | `/api/config/prompts` | 可自定义的提示词清单 |
| GET | `/api/config/prompts/{key}` | 读取提示词（自定义优先，含内置默认值） |
| PUT | `/api/config/prompts/{key}` | 保存自定义提示词（内容为空 = 恢复默认） |
| GET | `/api/config/api` | 获取 API 配置 |
| PUT | `/api/config/api` | 更新 API 配置 |

### 文档审核（`AuditController` / `AuditFeedbackController` / `AuditStatsController`）

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/rules` | 按规则组获取规则（审核用） |
| POST | `/api/audit` | 执行文档审核（同步） |
| POST | `/api/audit/stream` | 流式审核（NDJSON，逐条规则返回） |
| POST | `/api/parse` | 解析文档结构（返回章节树） |
| POST | `/api/proxy` | AI 代理请求 |
| POST | `/api/feedback/save` | 保存审核反馈（批量） |
| PUT | `/api/feedback/{id}` | 提交单条反馈 |
| GET | `/api/feedback/stats/{ruleId}` | 规则的反馈统计 |
| GET | `/api/feedback/failures/{ruleId}` | 规则的不准确反馈明细 |
| GET | `/api/stats` | 统计数据概览 |
| POST | `/api/stats/increment` | 累计调用次数 |
| POST | `/api/stats/duration` | 记录审核耗时 |
| GET | `/api/stats/daily` | 按日统计 |
| GET | `/api/stats/sources` | 按来源统计 |
| GET | `/api/stats/group/{groupId}` | 按规则组统计 |
| GET | `/api/template/default` | 下载默认文档模板 |

### 变更简报与工单（`OrderController` / `TicketController`）

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/order/search` | 按时间范围搜索工单（`startTime`/`endTime`） |
| GET | `/api/order/test-connection` | 工单接口连通性测试 |
| GET | `/api/order/{orderId}` | 取单条工单详情 |
| GET | `/api/order/audit-record` | 查询工单审核记录 |
| POST | `/api/order/async-audit` | 异步提交工单审核 |
| GET | `/api/order/async-task/{taskId}` | 查询工单审核任务状态 |
| POST | `/api/order/feedback/save` | 保存工单审核反馈 |
| PUT | `/api/order/feedback/{id}` | 提交单条工单反馈 |
| POST | `/api/order/summarize-stream` | 流式生成变更简报（NDJSON 事件流） |
| GET | `/api/order/brief-record` | 查询单份历史简报 |
| GET | `/api/order/brief-records` | 查询历史简报列表 |
| GET | `/api/ticket/{ticketId}` | 取工单信息（深链 loading） |
| POST | `/api/ticket/download` | 下载工单文档 |
| GET | `/api/ticket/audit-record` | 查询工单审核记录 |
| POST | `/api/ticket/async-audit` | 异步提交工单审核 |
| GET | `/api/ticket/async-task/{taskId}` | 查询工单审核任务状态 |

### 权限组（`PermissionGroupController`）

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/permission-groups` | 权限组列表 |
| GET | `/api/permission-groups/{permKey}` | 按 `?pgroup=` 参数取生效权限（查不到返回 `found:false`） |
| POST | `/api/permission-groups` | 新增权限组 |
| PUT | `/api/permission-groups/{id}` | 更新权限组 |
| DELETE | `/api/permission-groups/{id}` | 删除权限组 |

## 打包部署

```bash
cd backend
mvn clean package -DskipTests
```

生成 JAR: `target/smartdoc-backend-1.0.0.jar`

**启动方式:**
```bash
# 直接运行
java -jar target/smartdoc-backend-1.0.0.jar

# 使用启动脚本（推荐生产环境）
./start.sh    # Linux
start.bat     # Windows
```

**停止服务:**
```bash
./stop.sh     # Linux
stop.bat      # Windows
```

详细部署说明请参考 [backend/README.md](backend/README.md)

## 许可证

MIT License
