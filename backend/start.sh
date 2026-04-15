#!/bin/bash

APP_NAME="smartdoc-backend"
APP_VERSION="1.0.0"
JAR_FILE="target/${APP_NAME}-${APP_VERSION}.jar"

LOG_PATH="${LOG_PATH:-./logs}"
SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-prod}"
DB_URL="${DB_URL:-jdbc:mysql://localhost:3306/smartdoc?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-123456}"

JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC"

mkdir -p ${LOG_PATH}

echo "========================================"
echo "SmartDoc Backend Startup Script"
echo "========================================"
echo "Profile: ${SPRING_PROFILES_ACTIVE}"
echo "Log Path: ${LOG_PATH}"
echo "Database: ${DB_URL}"
echo "========================================"

if [ ! -f "$JAR_FILE" ]; then
    echo "JAR file not found: $JAR_FILE"
    echo "Please run 'mvn clean package -DskipTests' first"
    exit 1
fi

nohup java ${JAVA_OPTS} \
    -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE} \
    -DLOG_PATH=${LOG_PATH} \
    -DDB_URL=${DB_URL} \
    -DDB_USERNAME=${DB_USERNAME} \
    -DDB_PASSWORD=${DB_PASSWORD} \
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