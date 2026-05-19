@echo off
setlocal

set IMAGE_NAME=knowledge-base-frontend
set IMAGE_TAG=1.0.0
set OUTPUT_TAR=frontend.tar

echo [1/3] Build frontend dist...
call npm run build
if %errorlevel% neq 0 (
    echo [ERROR] Frontend build failed.
    exit /b 1
)
echo [1/3] OK - dist ready.

echo [2/3] Docker build %IMAGE_NAME%:%IMAGE_TAG%...
docker build -t %IMAGE_NAME%:%IMAGE_TAG% -t %IMAGE_NAME%:latest .
if %errorlevel% neq 0 (
    echo [ERROR] Docker build failed.
    exit /b 1
)
echo [2/3] OK - image built.

echo [3/3] Docker save to %OUTPUT_TAR%...
docker save -o %OUTPUT_TAR% %IMAGE_NAME%:%IMAGE_TAG%
if %errorlevel% neq 0 (
    echo [ERROR] Docker save failed.
    exit /b 1
)
echo [3/3] OK - %OUTPUT_TAR% ready.

echo Done! To load: docker load -i frontend.tar
endlocal
