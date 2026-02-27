#!/bin/bash
cd "$(dirname "$0")"

echo "========================================"
echo "   SmartDoc AI - 智能文档审核工具"
echo "========================================"
echo ""
echo "日志输出到 server.log"
echo "按 Ctrl+C 停止服务器"
echo "========================================"

nohup python3 -u server.py >> server.log 2>&1 &
echo "服务已在后台启动，PID: $!"
