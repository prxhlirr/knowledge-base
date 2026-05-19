# download_all_offline_assets.ps1
# Downloads Linux cp310 offline wheels for the lightweight Dockerfile.
# Policy: ONNXRuntime uses cuDNN 9; PaddleOCR runs on CPU only.

$reqFile = "e:\project\AI\knowledge-base\ai_service\requirements.txt"
$baseOutDir = "e:\project\AI\knowledge-base\ai_service\ai-lib"
$paddleOutDir = "$baseOutDir\paddle"

if (-not (Test-Path $baseOutDir)) { New-Item -ItemType Directory -Force -Path $baseOutDir | Out-Null }
if (-not (Test-Path $paddleOutDir)) { New-Item -ItemType Directory -Force -Path $paddleOutDir | Out-Null }

Write-Host "=== Downloading offline assets for AI service ===" -ForegroundColor Cyan

Write-Host "`n[1/3] Downloading requirements wheels, including cuDNN9 and ONNXRuntime GPU..." -ForegroundColor Yellow
pip download -r $reqFile `
    --platform manylinux2014_x86_64 `
    --python-version 310 `
    --only-binary=:all: `
    -d $baseOutDir `
    -i https://pypi.tuna.tsinghua.edu.cn/simple `
    --extra-index-url https://aiinfra.pkgs.visualstudio.com/PublicPackages/_packaging/onnxruntime-cuda-12/pypi/simple/

Write-Host "`n[2/3] Downloading PaddleOCR support wheels..." -ForegroundColor Yellow
pip download paddleocr paddlex decorator gast opt_einsum astor opencv-python-headless `
    --platform manylinux2014_x86_64 `
    --python-version 310 `
    --only-binary=:all: `
    -d $paddleOutDir `
    -i https://pypi.tuna.tsinghua.edu.cn/simple

Write-Host "`n[3/3] Downloading CPU PaddlePaddle runtime..." -ForegroundColor Yellow
pip download paddlepaddle==2.6.2 `
    --platform manylinux2014_x86_64 `
    --python-version 310 `
    --only-binary=:all: `
    -d $paddleOutDir `
    -i https://pypi.tuna.tsinghua.edu.cn/simple

if ($LASTEXITCODE -ne 0) {
    Write-Warning "CPU paddlepaddle download failed. Download paddlepaddle==2.6.2 cp310 Linux wheel manually into ai-lib/paddle/."
}

Write-Host "`n=== Downloads complete ===" -ForegroundColor Cyan
Write-Host "Expected: ai-lib contains nvidia_cudnn_cu12-9*.whl and ai-lib/paddle contains paddlepaddle-*.whl, not paddlepaddle_gpu*.whl."
