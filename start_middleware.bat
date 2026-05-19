@echo off
setlocal
chcp 65001 > nul

rem 业务功能: Windows 环境下一键启动 Docker 中间件
rem 关键说明: 以 utf-8 编码输出中文，依赖 docker-compose

echo --- 正在启动 Docker 中间件服务 ---

docker info >nul 2>nul
if %errorlevel% neq 0 (
    echo 错误: Docker 未运行，请先启动 Docker Desktop。
    pause
    exit /b 1
)

docker-compose up -d

echo --- 服务状态检查 ---
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" 

echo ---
echo Redis: localhost:6379
echo ES: localhost:9200
echo Kafka: localhost:9092
echo MinIO Console: http://localhost:9001 (User: admin, PWD: password123)
echo --- 启动完成 ---
pause
