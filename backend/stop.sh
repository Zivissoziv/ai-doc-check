#!/bin/bash

APP_NAME="smartdoc-backend"
LOG_PATH="${LOG_PATH:-./logs}"
PID_FILE="${LOG_PATH}/${APP_NAME}.pid"

echo "========================================"
echo "SmartDoc Backend Stop Script"
echo "========================================"

if [ ! -f "$PID_FILE" ]; then
    echo "PID file not found: $PID_FILE"
    echo "Application may not be running or was started manually"
    
    PID=$(ps aux | grep "smartdoc-backend" | grep -v grep | awk '{print $2}')
    if [ -z "$PID" ]; then
        echo "No running process found"
        exit 0
    fi
else
    PID=$(cat ${PID_FILE})
fi

if [ -z "$PID" ]; then
    echo "No PID found"
    exit 0
fi

echo "Stopping process with PID: ${PID}"

kill ${PID}

sleep 3

if ps -p ${PID} > /dev/null; then
    echo "Process still running, forcing kill..."
    kill -9 ${PID}
fi

if [ -f "$PID_FILE" ]; then
    rm ${PID_FILE}
fi

echo "Application stopped successfully"