Write-Host "=== 1. 清理旧镜像与悬挂镜像 ==="
docker rmi -f knowledge-base-java:1.0.0 knowledge-base-frontend:1.0.0 knowledge-base-ai:1.0.0
docker image prune -f

Write-Host "`n=== 2. 开始构建 Java 服务 ==="
Set-Location "e:\project\AI\knowledge-base\java_service"
cmd.exe /c build-image.bat | Tee-Object -Variable "out_java"
if ($LASTEXITCODE -ne 0) { throw "Java 构建失败" }

Write-Host "`n=== 3. 开始构建 前端服务 ==="
Set-Location "e:\project\AI\knowledge-base\frontend"
cmd.exe /c build-image.bat | Tee-Object -Variable "out_front"
if ($LASTEXITCODE -ne 0) { throw "前端构建失败" }

Write-Host "`n=== 4. 开始构建 AI 服务 ==="
Set-Location "e:\project\AI\knowledge-base\ai_service"
cmd.exe /c build-image.bat | Tee-Object -Variable "out_ai"
if ($LASTEXITCODE -ne 0) { throw "AI 构建失败" }

Write-Host "`n=== !!! 全量构建完毕 !!! ==="
