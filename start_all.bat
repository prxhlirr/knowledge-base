@echo off
chcp 65001 >nul
title 启动控制中心 - Knowledge Base
echo ===================================================
echo   项目启动脚本 (开发环境)
echo   包含: 基础设施, AI服务, Java服务, 前端服务
echo ===================================================
echo.

:: 1. 启动基础设施层 (Docker)
echo [1/4] 正在启动 Elasticsearch, Kibana 等基础设施...
docker-compose up -d
if %errorlevel% neq 0 (
    echo [错误] Docker 容器启动失败，请检查 Docker 是否运行。
    pause
    exit /b %errorlevel%
)
echo 基础设施启动指令已下发。
echo.

:: 2. 启动 AI 服务层 (Python)
echo [2/4] 正在启动 AI Service...
if exist "ai_service\venv\Scripts\activate.bat" (
    start "AI Service" cmd /k "cd ai_service && call venv\Scripts\activate.bat && python main.py"
    echo AI Service 启动窗口已打开。
) else (
    echo [警告] 未找到 ai_service\venv 虚拟环境！请确认环境配置。
    start "AI Service" cmd /k "cd ai_service && python main.py"
)
echo.

:: 3. 启动 Java 后端服务层 (Spring Boot)
echo [3/4] 正在启动 Java Service (每次编译)...
if exist "java_service\pom.xml" (
    start "Java Service" cmd /k "cd java_service && mvn spring-boot:run"
    echo Java Service 启动窗口已打开。
) else (
    echo [警告] 未找到 java_service 目录或 pom.xml，跳过 Java 服务启动。
)
echo.

:: 4. 启动前端服务层 (Vue / Vite)
echo [4/4] 正在启动 Frontend Service (仅开发服务器)...
if exist "frontend\package.json" (
    start "Frontend" cmd /k "cd frontend && npm run dev"
    echo Frontend 启动窗口已打开。
) else (
    echo [警告] 未找到 frontend 目录或 package.json，跳过前端启动。
)
echo.

echo ===================================================
echo   所有模块的启动指令已发出！
echo   请在各自的弹窗中查看日志。
echo   关闭服务请使用 stop_all.bat。
echo ===================================================
pause
