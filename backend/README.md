# SmartDoc Backend

基于 Spring Boot 的智能文档审核系统后端服务。

## 技术栈

- **JDK**: 1.8
- **Spring Boot**: 2.7.18
- **Maven**: 3.x
- **MySQL**: 8.0+
- **JPA/Hibernate**: ORM框架

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
│   │   │   ├── repository/                 # 数据访问层
│   │   │   └── service/                    # 业务逻辑层
│   │   └── resources/
│   │       ├── application.yml             # 应用配置
│   │       └── db/                         # 数据库脚本
│   └── test/                               # 测试代码
└── pom.xml                                 # Maven配置
```

## 快速开始

### 1. 环境准备

- 安装 JDK 1.8
- 安装 Maven 3.x
- 安装 MySQL 8.0+

### 2. 数据库配置

```sql
# 创建数据库
CREATE DATABASE smartdoc DEFAULT CHARACTER SET utf8mb4;

# 执行初始化脚本
source src/main/resources/db/init.sql
```

### 3. 修改配置

编辑 `src/main/resources/application.yml`，修改数据库连接信息：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/smartdoc?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai
    username: your_username
    password: your_password
```

### 4. 构建项目

```bash
cd backend
mvn clean install
```

### 5. 运行项目

```bash
mvn spring-boot:run
```

服务将在 `http://localhost:8080` 启动。

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

## 运行测试

```bash
mvn test
```

## 打包部署

```bash
mvn clean package -DskipTests
```

生成的 JAR 文件位于 `target/smartdoc-backend-1.0.0.jar`

## 开发规范

1. **代码风格**: 遵循阿里巴巴 Java 开发手册
2. **命名规范**: 类名使用大驼峰，方法名使用小驼峰
3. **注释规范**: 公共方法必须添加 Javadoc 注释
4. **单元测试**: 核心业务逻辑必须有单元测试覆盖
5. **异常处理**: 使用统一的异常处理机制

## 许可证

MIT License