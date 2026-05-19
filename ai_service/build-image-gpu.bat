@echo off
setlocal

:: ============================================================
:: build-image-gpu.bat
:: 用途：构建 knowledge-base-ai:gpu（Phase 2 扫描件支持版）
::       使用 Dockerfile.gpu，离线安装 PaddleOCR 依赖
::
:: 前提：
::   1. ai-lib\paddle\ 目录中必须包含：
::      - paddlepaddle_gpu-2.6.1.post120-cp310-cp310-linux_x86_64.whl (~930MB)
::        运行 download_paddle_gpu_whl.ps1 下载
::      - paddleocr-2.8.1-py3-none-any.whl
::      - paddlex-2.1.0-py3-none-any.whl
::      - opencv_python_headless-4.13.0.92-cp37-abi3-*-x86_64.whl
::        运行 download_paddle_whl.ps1 中 [1/3] [3/3] 步骤下载
::   2. AI 模型目录已准备：
::      - models\onnx_native\ (BGE-M3 / Reranker)
::      - models\paddle_ocr\  (PP-OCRv4 中文模型)
::        模型挂载路径在 docker-compose.yml 中配置
:: ============================================================

set IMAGE_NAME=knowledge-base-ai
set IMAGE_TAG=gpu
set OUTPUT_TAR=ai-service-gpu.tar
set DOCKERFILE=Dockerfile.gpu

:: ── 预检：确认关键 whl 存在 ──────────────────────────────────────────────────
echo [0/3] Pre-check: verifying ai-lib\paddle\ contents...

set MISSING_WARN=0

if not exist "ai-lib\paddle\paddlepaddle_gpu*.whl" (
    echo   [WARN] paddlepaddle_gpu whl NOT found in ai-lib\paddle\
    echo          OCR will be unavailable. Run download_paddle_gpu_whl.ps1 to fix.
    set MISSING_WARN=1
) else (
    for %%F in ("ai-lib\paddle\paddlepaddle_gpu*.whl") do (
        echo   [OK] %%~nxF
    )
)

if not exist "ai-lib\paddle\paddleocr*.whl" (
    echo   [WARN] paddleocr whl NOT found. Run download_paddle_whl.ps1 first.
    set MISSING_WARN=1
) else (
    for %%F in ("ai-lib\paddle\paddleocr*.whl") do echo   [OK] %%~nxF
)

if not exist "ai-lib\paddle\opencv_python_headless*.whl" (
    echo   [WARN] opencv_python_headless whl NOT found.
    set MISSING_WARN=1
) else (
    for %%F in ("ai-lib\paddle\opencv_python_headless*.whl") do echo   [OK] %%~nxF
)

if not exist "ai-lib\paddle\paddlex*.whl" (
    echo   [WARN] paddlex whl NOT found.
    set MISSING_WARN=1
) else (
    for %%F in ("ai-lib\paddle\paddlex*.whl") do echo   [OK] %%~nxF
)

if %MISSING_WARN%==1 (
    echo.
    echo   [NOTE] Build will continue; missing paddle whl means OCR unavailable at runtime.
    echo   [NOTE] Press Ctrl+C to abort and fix dependencies, or wait 5s to continue...
    timeout /t 5 /nobreak > nul
)
echo [0/3] Pre-check done.

:: ── 步骤1：拷贝 scripts ───────────────────────────────────────────────────────
echo.
echo [1/3] Copying external scripts into build context...
xcopy /E /I /Y ..\scripts .\scripts > nul
if %errorlevel% neq 0 (
    echo [ERROR] Failed to copy scripts.
    exit /b 1
)
echo [1/3] OK

:: ── 步骤2：Docker build ───────────────────────────────────────────────────────
echo.
echo [2/3] Building %IMAGE_NAME%:%IMAGE_TAG% using %DOCKERFILE%...
echo       (This may take 10-30 minutes on first run due to paddle whl size)
docker build -f %DOCKERFILE% -t %IMAGE_NAME%:%IMAGE_TAG% .
if %errorlevel% neq 0 (
    echo [ERROR] Docker build FAILED. Check logs above.
    exit /b 1
)
echo [2/3] OK - image built: %IMAGE_NAME%:%IMAGE_TAG%

:: ── 步骤3：Docker save ───────────────────────────────────────────────────────
echo.
echo [3/3] Saving image to %OUTPUT_TAR% (may take several minutes)...
docker save -o %OUTPUT_TAR% %IMAGE_NAME%:%IMAGE_TAG%
if %errorlevel% neq 0 (
    echo [ERROR] Docker save FAILED.
    exit /b 1
)
echo [3/3] OK - %OUTPUT_TAR% ready for offline deployment.

:: ── 完成 ─────────────────────────────────────────────────────────────────────
echo.
echo ============================================================
echo  Build SUCCESS
echo  Image  : %IMAGE_NAME%:%IMAGE_TAG%
echo  Archive: %OUTPUT_TAR%
echo ============================================================
echo  Deploy:  docker load -i %OUTPUT_TAR%
echo           docker-compose -f docker-compose-prod.yml up -d ai-service
echo ============================================================

endlocal
