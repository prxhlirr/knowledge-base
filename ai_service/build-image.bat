@echo off
setlocal

set IMAGE_NAME=knowledge-base-ai
set IMAGE_TAG=1.0.0
set OUTPUT_TAR=ai-service.tar

REM BuildKit 必须启用，Dockerfile 中使用了 --mount=type=bind 语法
REM 该特性让 ai-lib/ 在构建时临时挂载而不写入镜像层，节省约 2GB
set DOCKER_BUILDKIT=1

echo [0/2] Copying external scripts into build context...
xcopy /E /I /Y ..\scripts .\scripts

echo [1/2] Docker build %IMAGE_NAME%:%IMAGE_TAG%...
REM 获取当前时间戳用于破坏 COPY core/ 的 Docker 缓存层
REM 根因：BuildKit on Windows 有时无法检测 core/ 文件变更，导致新代码不进镜像
for /f %%i in ('powershell -Command "Get-Date -Format yyyyMMddHHmmss"') do set BUILD_TIME=%%i
docker build -t %IMAGE_NAME%:%IMAGE_TAG% -t %IMAGE_NAME%:latest --build-arg CACHEBUST=%BUILD_TIME% .
if %errorlevel% neq 0 (
    echo [ERROR] Docker build failed.
    exit /b 1
)
echo [1/2] OK - image built.

echo [2/2] Docker save to %OUTPUT_TAR%...
docker save -o %OUTPUT_TAR% %IMAGE_NAME%:%IMAGE_TAG%
if %errorlevel% neq 0 (
    echo [ERROR] Docker save failed.
    exit /b 1
)
echo [2/2] OK - %OUTPUT_TAR% ready.

echo Done! To load: docker load -i ai-service.tar
endlocal
