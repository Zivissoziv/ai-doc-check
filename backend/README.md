# SmartDoc Backend

基于 Spring Boot 的智能文档审核系统后端服务。

## 技术栈

- **JDK**: 1.8
- **Spring Boot**: 2.7.18
- **Maven**: 3.x
- **MySQL**: 8.0+
- **MyBatis-Plus**: 3.5.3.1

## 项目结构

```
backend/
├── src/
│   ├── main/
│   │   ├── java/com/smartdoc/
│   │   │   ├── SmartDocApplication.java    # 启动类
│   │   │   ├── config/                     # 配置类
│   │   │   ├── controller/                 # REST控制器
│   │   │   ├── dto/                        # 数据传输对象
│   │   │   ├── entity/                     # 实体类
│   │   │   ├── exception/                  # 异常处理
│   │   │   ├── mapper/                     # 数据访问层
│   │   │   └── service/                    # 业务逻辑层
│   │   └── resources/
│   │       ├── application.yml             # 主配置文件
│   │       ├── application-dev.yml         # 开发环境配置
│   │       ├── application-prod.yml        # 生产环境配置
│   │       ├── logback-spring.xml          # 日志配置
│   │       └── db/                         # 数据库脚本
│   └── test/                               # 测试代码
├── start.sh                                # Linux启动脚本
├── start.bat                               # Windows启动脚本
├── stop.sh                                 # Linux停止脚本
├── stop.bat                                # Windows停止脚本
└ pom.xml                                   # Maven配置
```

## 快速开始

### 1. 环境准备

- 安装 JDK 1.8
- 安装 Maven 3.x
- 安装 MySQL 8.0+

### 2. 数据库配置

```sql
CREATE DATABASE smartdoc DEFAULT CHARACTER SET utf8mb4;
USE smartdoc;
source src/main/resources/db/init.sql;
```

### 3. 构建项目

```bash
cd backend
mvn clean install -DskipTests
```

### 4. 运行项目

**开发环境 (默认):**
```bash
# 方式1: Maven 直接运行（推荐开发调试）
mvn spring-boot:run

# 方式2: 打包后运行
mvn clean package -DskipTests
java -jar target/smartdoc-backend-1.0.0.jar
```

**生产环境 (使用启动脚本):**
```bash
# 1. 先打包
mvn clean package -DskipTests

# 2. Linux 启动
chmod +x start.sh
./start.sh

# 3. Windows 启动
start.bat
```

**启动脚本说明:**

启动脚本 `start.sh` / `start.bat` 支持通过环境变量配置：

```bash
# Linux 示例：自定义配置启动
export SPRING_PROFILES_ACTIVE=prod
export DB_URL=jdbc:mysql://192.168.1.100:3306/smartdoc
export DB_USERNAME=smartdoc
export DB_PASSWORD=your_password
export LOG_PATH=/var/log/smartdoc
./start.sh
```

```cmd
# Windows 示例：自定义配置启动
set SPRING_PROFILES_ACTIVE=prod
set DB_URL=jdbc:mysql://192.168.1.100:3306/smartdoc
set DB_USERNAME=smartdoc
set DB_PASSWORD=your_password
set LOG_PATH=C:\logs\smartdoc
start.bat
```

**停止服务:**
```bash
# Linux
./stop.sh

# Windows
stop.bat
```

服务将在 `http://localhost:8080` 启动。

## 环境配置

项目支持多环境配置，通过 `SPRING_PROFILES_ACTIVE` 环境变量切换：

| 环境 | 配置文件 | 特点 |
|------|----------|------|
| dev | application-dev.yml | DEBUG日志、SQL输出、本地数据库 |
| prod | application-prod.yml | INFO日志、环境变量配置、生产优化 |

**切换环境:**
```bash
# 方式1: 环境变量
export SPRING_PROFILES_ACTIVE=prod

# 方式2: 启动参数
java -jar target/smartdoc-backend-1.0.0.jar --spring.profiles.active=prod
```

## 生产环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `SPRING_PROFILES_ACTIVE` | 运行环境 | dev |
| `DB_URL` | 数据库连接URL | localhost:3306/smartdoc |
| `DB_USERNAME` | 数据库用户名 | root |
| `DB_PASSWORD` | 数据库密码 | 123456 |
| `LOG_PATH` | 日志目录 | ./logs (dev) / /var/log/smartdoc (prod) |

## 日志配置

日志配置文件: `logback-spring.xml`

**日志文件:**
| 文件 | 说明 | 保留策略 |
|------|------|----------|
| smartdoc.log | 主日志文件 | 30天, 100MB/文件 |
| smartdoc-error.log | 错误日志 | 30天, 50MB/文件 |
| smartdoc-audit.log | 审核日志 | 90天, 200MB/文件 |

**日志级别:**
- 开发环境: DEBUG (控制台输出)
- 生产环境: INFO (文件输出 + 控制台)

## API 接口

### 规则组管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/config/rules` | 获取所有规则组 |
| GET | `/api/config/rules/{groupId}` | 获取指定规则组的规则 |
| POST | `/api/config/rules` | 创建规则组 |
| PUT | `/api/config/rules/{groupId}` | 更新规则组 |
| DELETE | `/api/config/rules/{groupId}` | 删除规则组 |

### API配置管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/config/api` | 获取API配置 |
| PUT | `/api/config/api` | 更新API配置 |

### 文档审核

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | `/api/audit` | 执行文档审核 |
| POST | `/api/proxy` | AI代理请求 |

## 打包部署

```bash
mvn clean package -DskipTests
```

生成 JAR: `target/smartdoc-backend-1.0.0.jar`

**部署步骤:**
```bash
# 1. 上传JAR和启动脚本到服务器
scp target/smartdoc-backend-1.0.0.jar server:/opt/smartdoc/
scp start.sh stop.sh server:/opt/smartdoc/

# 2. 配置环境变量
export SPRING_PROFILES_ACTIVE=prod
export DB_URL=jdbc:mysql://prod-db:3306/smartdoc
export DB_USERNAME=smartdoc
export DB_PASSWORD=secure_password
export LOG_PATH=/var/log/smartdoc

# 3. 启动服务
chmod +x start.sh
./start.sh
```

## 开发规范

1. **代码风格**: 遵循阿里巴巴 Java 开发手册
2. **命名规范**: 类名使用大驼峰，方法名使用小驼峰
3. **注释规范**: 公共方法必须添加 Javadoc 注释
4. **单元测试**: 核心业务逻辑必须有单元测试覆盖
5. **异常处理**: 使用统一的异常处理机制
6. **日志规范**: 使用 Slf4j + Lombok @Slf4j 注解

## 许可证

MIT License