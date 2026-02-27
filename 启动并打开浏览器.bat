@echo off
chcp 65001 >nul
title SmartDoc AI - 文档审核工具

echo ========================================
echo    SmartDoc AI - 智能文档审核工具
echo ========================================
echo.
echo 正在启动本地服务器...
echo 3秒后自动打开浏览器...
echo.
echo 按 Ctrl+C 可停止服务器
echo ========================================
echo.

:: 延迟后打开浏览器
start "" cmd /c "timeout /t 2 /nobreak >nul && start http://localhost:8000"

:: 启动HTTP服务器
python server.py

pause
