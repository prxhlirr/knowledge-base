@echo off
setlocal

set IMAGE_NAME=knowledge-base-java
set IMAGE_TAG=1.0.0
set OUTPUT_TAR=java-service.tar

echo.
echo [1/3] Maven package (skip tests)...
call mvn package -DskipTests -q
if %errorlevel% neq 0 (
    echo [ERROR] Maven build failed.
    exit /b 1
)
echo [1/3] OK - target\knowledge-base-1.0.0-SNAPSHOT.jar

echo.
echo [2/3] Docker build %IMAGE_NAME%:%IMAGE_TAG%...
docker build -t %IMAGE_NAME%:%IMAGE_TAG% -t %IMAGE_NAME%:latest .
if %errorlevel% neq 0 (
    echo [ERROR] Docker build failed.
    exit /b 1
)
echo [2/3] OK - image built.

echo.
echo [3/3] Docker save to %OUTPUT_TAR%...
docker save -o %OUTPUT_TAR% %IMAGE_NAME%:%IMAGE_TAG%
if %errorlevel% neq 0 (
    echo [ERROR] Docker save failed.
    exit /b 1
)
echo [3/3] OK - %OUTPUT_TAR% ready.

echo.
echo Done! To load on target server: docker load -i java-service.tar
endlocal
