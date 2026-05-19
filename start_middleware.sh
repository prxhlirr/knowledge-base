#!/bin/bash

# 业务功能: 一键启动核心中间件 (Redis, ES, Kafka, MinIO)
# 关键说明: 使用 docker-compose 进行编排

echo "--- 正在启动 Docker 中间件服务 ---"

# 检查 Docker 是否运行
if ! docker info > /dev/null 2>&1; then
    echo "错误: Docker 未运行，请先启动 Docker Desktop。"
    exit 1
fi

# 启动服务
docker-compose up -d

# 检查启动结果
echo "--- 服务状态检查 ---"
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | grep -E "redis|es86|kafka|minio"

echo "---"
echo "Redis: localhost:6379"
echo "ES: localhost:9200"
echo "Kafka: localhost:9092"
echo "MinIO Console: http://localhost:9001 (User: admin, PWD: password123)"
echo "--- 启动完成 ---"
