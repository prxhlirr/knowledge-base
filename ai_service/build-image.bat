@echo off
setlocal

set IMAGE_NAME=knowledge-base-ai
set IMAGE_TAG=1.0.0
set OUTPUT_TAR=ai-service.tar

echo [0/2] Copying external scripts into build context...
xcopy /E /I /Y ..\scripts .\scripts

echo [1/2] Docker build %IMAGE_NAME%:%IMAGE_TAG%...
docker build -t %IMAGE_NAME%:%IMAGE_TAG% -t %IMAGE_NAME%:latest .
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
