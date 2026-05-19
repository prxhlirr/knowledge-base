# download_paddle_whl.ps1
# Downloads PaddleOCR dependencies for Linux cp310.
# Policy: OCR runs on CPU only; do not download paddlepaddle-gpu or cuDNN8.

$outDir = "e:\project\AI\knowledge-base\ai_service\ai-lib\paddle"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }

Write-Host "=== PaddleOCR CPU dependency downloader ===" -ForegroundColor Cyan
Write-Host "Output: $outDir`n"

Write-Host "[1/3] Downloading paddleocr, paddlex, and helper wheels..." -ForegroundColor Yellow
pip download paddleocr paddlex decorator gast opt_einsum astor `
    --platform manylinux2014_x86_64 `
    --python-version 310 `
    --only-binary=:all: `
    -d $outDir `
    -i https://pypi.tuna.tsinghua.edu.cn/simple

Write-Host "`n[2/3] Downloading opencv-python-headless..." -ForegroundColor Yellow
pip download opencv-python-headless `
    --platform manylinux2014_x86_64 `
    --python-version 310 `
    --only-binary=:all: `
    -d $outDir `
    -i https://pypi.tuna.tsinghua.edu.cn/simple

Write-Host "`n[3/3] Downloading CPU paddlepaddle==2.6.2..." -ForegroundColor Yellow
pip download paddlepaddle==2.6.2 `
    --platform manylinux2014_x86_64 `
    --python-version 310 `
    --only-binary=:all: `
    -d $outDir `
    -i https://pypi.tuna.tsinghua.edu.cn/simple

if ($LASTEXITCODE -ne 0) {
    Write-Warning "CPU paddlepaddle download failed. Download paddlepaddle==2.6.2 cp310 Linux wheel manually into ai-lib/paddle/."
}

Write-Host "`n=== Download result ===" -ForegroundColor Cyan
Get-ChildItem $outDir -Filter "*.whl" | Select-Object Name, @{N='MB';E={[math]::Round($_.Length/1MB,1)}} | Format-Table -AutoSize
Write-Host "Do not keep paddlepaddle_gpu*.whl in ai-lib/paddle when building CPU-only OCR images." -ForegroundColor Yellow
