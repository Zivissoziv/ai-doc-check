# SmartDoc AI - 智能文档审核系统

基于 Spring Boot + 原生前端实现的智能文档审核工具，支持文档结构对比和 AI 内容审核。

## 项目架构

```
ai-doc-check/
├── backend/                 # Spring Boot 后端服务
│   ├── src/main/java/       # Java 源码
│   ├── src/main/resources/  # 配置文件与静态资源
│   └ pom.xml                # Maven 配置
│   └ README.md              # 后端详细文档
├── frontend/                # 前端静态文件
│   ├── js/                  # JavaScript 模块
│   ├── libs/                # 第三方库
│   ├── app.js               # 主应用逻辑
│   └ index.html             # 入口页面
│   └ styles.css             # 样式文件
└── README.md                # 本文件
```

## 技术栈

### 后端
- **JDK**: 1.8
- **Spring Boot**: 2.7.18
- **MyBatis-Plus**: 3.5.3.1
- **MySQL**: 8.0+
- **Apache POI**: 5.2.3 (Word 文档解析)
- **Apache PDFBox**: 2.0.29 (PDF 解析)

### 前端
- **原生 JavaScript** (无框架依赖)
- **Tailwind CSS** (样式框架)
- **Mammoth.js** (浏览器端 Word 解析)
- **PDF.js** (浏览器端 PDF 解析)
- **SheetJS** (Excel 数据导入)

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

**生产环境 (使用启动脚本):**
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

详细配置说明请参考 [backend/README.md](backend/README.md)

## 核心功能

### 文档结构对比
- 上传模板文档与待审文档
- 自动提取文档章节结构
- 智能匹配章节标题（支持模糊匹配）
- 精确匹配模式下对比章节内容相似度

### AI 内容审核
- 自定义审核规则（自然语言描述）
- 规则组管理（多套审核标准）
- Excel 数据源引用（动态数据注入）
- 批量审核与结果导出

### 相似度算法

**标题匹配:**
- Jaccard 字符集相似度
- 层级匹配加权 (+0.2)
- 关键词提取匹配（如"第一章"）

**内容相似度:**
- 中文: `Jaccard × 0.8 + 长度比例 × 0.2 + 公共前缀奖励`
- 英文: 单词级 Jaccard 相似度

## API 接口

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/config/rules` | 获取所有规则组 |
| GET | `/api/config/rules/{groupId}` | 获取指定规则组 |
| POST | `/api/config/rules` | 创建规则组 |
| PUT | `/api/config/rules/{groupId}` | 更新规则组 |
| DELETE | `/api/config/rules/{groupId}` | 删除规则组 |
| GET | `/api/config/api` | 获取 API 配置 |
| PUT | `/api/config/api` | 更新 API 配置 |
| POST | `/api/audit` | 执行文档审核 |
| POST | `/api/proxy` | AI 代理请求 |

## 打包部署

```bash
cd backend
mvn clean package -DskipTests
```

生成 JAR: `target/smartdoc-backend-1.0.0.jar`

**启动方式:**
```bash
# 方式1: 直接运行
java -jar target/smartdoc-backend-1.0.0.jar

# 方式2: 使用启动脚本 (推荐生产环境)
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