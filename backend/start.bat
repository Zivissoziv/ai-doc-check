@echo off
setlocal

set APP_NAME=smartdoc-backend
set APP_VERSION=1.0.0
set JAR_FILE=target\%APP_NAME-%APP_VERSION%.jar

if "%LOG_PATH%"=="" set LOG_PATH=.\logs
if "%SPRING_PROFILES_ACTIVE%"=="" set SPRING_PROFILES_ACTIVE=prod
if "%DB_URL%"=="" set DB_URL=jdbc:mysql://localhost:3306/smartdoc?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai
if "%DB_USERNAME%"=="" set DB_USERNAME=root
if "%DB_PASSWORD%"=="" set DB_PASSWORD=123456

set JAVA_OPTS=-Xms512m -Xmx1024m -XX:+UseG1GC

if not exist "%LOG_PATH%" mkdir "%LOG_PATH%"

echo ========================================
echo SmartDoc Backend Startup Script
echo ========================================
echo Profile: %SPRING_PROFILES_ACTIVE%
echo Log Path: %LOG_PATH%
echo Database: %DB_URL%
echo ========================================

if not exist "%JAR_FILE%" (
    echo JAR file not found: %JAR_FILE%
    echo Please run 'mvn clean package -DskipTests' first
    exit /b 1
)

start /b java %JAVA_OPTS% ^
    -Dspring.profiles.active=%SPRING_PROFILES_ACTIVE% ^
    -DLOG_PATH=%LOG_PATH% ^
    -DDB_URL=%DB_URL% ^
    -DDB_USERNAME=%DB_USERNAME% ^
    -DDB_PASSWORD=%DB_PASSWORD% ^
    -jar %JAR_FILE%

echo Application started
echo Startup log: %LOG_PATH%\startup.log
echo Access: http://localhost:8080

endlocal