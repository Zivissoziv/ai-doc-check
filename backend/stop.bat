@echo off

echo ========================================
echo SmartDoc Backend Stop Script
echo ========================================

for /f "tokens=5" %%a in ('netstat -aon ^| findstr :8080 ^| findstr LISTENING') do (
    set PID=%%a
)

if "%PID%"=="" (
    echo No process found listening on port 8080
    exit /b 0
)

echo Stopping process with PID: %PID%

taskkill /F /PID %PID%

echo Application stopped successfully