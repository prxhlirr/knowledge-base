# download_paddle_gpu_whl.ps1
# Downloads paddlepaddle-gpu whl from the official Paddle CDN (Baidu BCE).
# Uses Windows built-in curl.exe (v8+) which handles large files reliably.
#
# Target: Python 3.10 / CUDA 12.0 / cuDNN 8.9 / Linux x86_64
# Usage (from ai_service directory):
#   powershell -ExecutionPolicy Bypass -File download_paddle_gpu_whl.ps1

$OutputDir = Join-Path $PSScriptRoot "ai-lib\paddle"
$WhlName   = "paddlepaddle_gpu-2.6.1.post120-cp310-cp310-linux_x86_64.whl"
$WhlUrl    = "https://paddle-wheel.bj.bcebos.com/2.6.1/linux/linux-gpu-cuda12.0-cudnn8.9-mkl-gcc12.2-avx/$WhlName"
$OutPath   = Join-Path $OutputDir $WhlName

# Ensure output directory exists
if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    Write-Host "[INFO] Created: $OutputDir"
}

# Skip if already downloaded with correct size (> 800 MB means complete)
if (Test-Path $OutPath) {
    $sizeMB = [math]::Round((Get-Item $OutPath).Length / 1MB, 1)
    if ($sizeMB -gt 800) {
        Write-Host "[SKIP] Already exists and complete: $WhlName ($sizeMB MB)"
        exit 0
    } else {
        Write-Host "[INFO] Incomplete download detected ($sizeMB MB), resuming..."
    }
}

# Check curl.exe availability (Windows 10/11 built-in, PS 5 compatible)
$curlCmd  = Get-Command curl.exe -ErrorAction SilentlyContinue
$curlPath = if ($curlCmd) { $curlCmd.Source } else { $null }
if (-not $curlPath) {
    Write-Host "[ERROR] curl.exe not found. Please install curl or download manually." -ForegroundColor Red
    Write-Host "  URL: $WhlUrl"
    exit 1
}

Write-Host ""
Write-Host "===================================================" -ForegroundColor Cyan
Write-Host "  PaddlePaddle GPU whl Downloader" -ForegroundColor Cyan
Write-Host "===================================================" -ForegroundColor Cyan
Write-Host "  Package: paddlepaddle-gpu 2.6.1.post120"
Write-Host "  Python : cp310 (3.10)"
Write-Host "  CUDA   : 12.0 + cuDNN 8.9"
Write-Host "  Size   : ~930 MB"
Write-Host "  Tool   : $curlPath"
Write-Host "  Target : $OutPath"
Write-Host ""
Write-Host "  Downloading with curl (supports resume, progress bar)..."
Write-Host ""

$startTime = Get-Date

# curl.exe flags:
#   -L  : follow redirects (BCE CDN may redirect)
#   -C - : auto-resume from partial download
#   --retry 3 --retry-delay 5 : retry on transient errors
#   --progress-bar : show progress (cleaner than verbose)
#   -o  : output file
$curlArgs = @(
    "-L",
    "-C", "-",
    "--retry", "3",
    "--retry-delay", "5",
    "--progress-bar",
    "-o", $OutPath,
    $WhlUrl
)

& curl.exe @curlArgs

$exitCode = $LASTEXITCODE
$elapsed  = [math]::Round(((Get-Date) - $startTime).TotalSeconds, 0)

if ($exitCode -eq 0 -and (Test-Path $OutPath)) {
    $sizeMB = [math]::Round((Get-Item $OutPath).Length / 1MB, 1)
    if ($sizeMB -gt 800) {
        Write-Host ""
        Write-Host "===================================================" -ForegroundColor Green
        Write-Host "  Download complete!" -ForegroundColor Green
        Write-Host "  File   : $WhlName" -ForegroundColor Green
        Write-Host "  Size   : $sizeMB MB  |  Time: ${elapsed}s" -ForegroundColor Green
        Write-Host "===================================================" -ForegroundColor Green
    } else {
        Write-Host ""
        Write-Host "[WARN] File size $sizeMB MB seems too small, may be incomplete." -ForegroundColor Yellow
        Write-Host "       Re-run this script to resume."
        exit 1
    }
} else {
    Write-Host ""
    Write-Host "[ERROR] curl exited with code $exitCode" -ForegroundColor Red
    Write-Host ""
    Write-Host "Manual download:" -ForegroundColor Yellow
    Write-Host "  URL  : $WhlUrl"
    Write-Host "  Save : $OutPath"
    Write-Host ""
    Write-Host "Or browse: https://www.paddlepaddle.org.cn/whl/linux/mkl/avx/stable.html"
    Write-Host "  Find: paddlepaddle_gpu-2.6.1.post120-cp310-cp310-linux_x86_64.whl"
    exit 1
}

Write-Host ""
Write-Host "Contents of ai-lib\paddle\:"
Get-ChildItem $OutputDir | Select-Object Name, @{N='MB';E={[math]::Round($_.Length/1MB,1)}} | Format-Table -AutoSize
Write-Host "Next step: run build-image-gpu.bat" -ForegroundColor Cyan
