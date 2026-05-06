#!/bin/bash

APP_NAME="smartdoc-backend"
APP_VERSION="1.0.0"
JAR_FILE="target/${APP_NAME}-${APP_VERSION}.jar"

LOG_PATH="${LOG_PATH:-./logs}"
SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-prod}"

# 如果存在 config/ 外部配置文件，直接使用（无需设置环境变量）
# 否则回退到环境变量方式
CONFIG_DIR="./config"
if [ -d "$CONFIG_DIR" ] && [ "$(ls -A $CONFIG_DIR/*.properties 2>/dev/null)" ]; then
    echo "使用外部配置文件: ${CONFIG_DIR}/application-${SPRING_PROFILES_ACTIVE}.properties"
    CONFIG_OPTS=""
else
    echo "未检测到外部配置文件，使用环境变量方式"
    CONFIG_OPTS="-DDB_URL=${DB_URL:-jdbc:mysql://localhost:3306/smartdoc?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai} -DDB_USERNAME=${DB_USERNAME:-root} -DDB_PASSWORD=${DB_PASSWORD:-123456}"
fi

JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC"

mkdir -p ${LOG_PATH}

echo "========================================"
echo "SmartDoc Backend Startup Script"
echo "========================================"
echo "Profile: ${SPRING_PROFILES_ACTIVE}"
echo "Log Path: ${LOG_PATH}"
echo "Config: ${CONFIG_DIR:-环境变量}"
echo "========================================"

if [ ! -f "$JAR_FILE" ]; then
    echo "JAR file not found: $JAR_FILE"
    echo "Please run 'mvn clean package -DskipTests' first"
    exit 1
fi

nohup java ${JAVA_OPTS} \
    -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE} \
    -DLOG_PATH=${LOG_PATH} \
    ${CONFIG_OPTS} \
    -jar ${JAR_FILE} \
    > ${LOG_PATH}/startup.log 2>&1 &

PID=$!
echo "Application started with PID: ${PID}"
echo "Startup log: ${LOG_PATH}/startup.log"

echo ${PID} > ${LOG_PATH}/${APP_NAME}.pid

sleep 3

if ps -p ${PID} > /dev/null; then
    echo "Application is running successfully!"
    echo "Access: http://localhost:8080"
else
    echo "Application failed to start. Check startup log for details."
    exit 1
fi
