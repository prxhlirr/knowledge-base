$reqFile = "e:\project\AI\knowledge-base\ai_service\requirements.txt"
$outDir = "e:\project\AI\knowledge-base\ai_service\ai-lib"
$tmpFile = "e:\project\AI\knowledge-base\ai_service\tmp_req.txt"

Write-Host "正在提取通用依赖列表..."
Get-Content $reqFile | Where-Object { 
    $_.Trim() -ne "" -and 
    (-not $_.StartsWith("#")) -and 
    (-not $_.Contains("onnxruntime-gpu")) -and
    (-not $_.Contains("torch")) -and
    (-not $_.Contains("python-magic-bin")) -and
    (-not $_.Contains("paddleocr")) -and
    (-not $_.Contains("opencv")) -and
    (-not $_.Contains("jieba"))
} | Out-File $tmpFile -Encoding utf8

Add-Content -Path $tmpFile -Value "python-magic" -Encoding utf8

Write-Host "需要跨平台下载的包列表如下："
Get-Content $tmpFile | Write-Host

Write-Host "`n开始跨平台下载 Linux 依赖包到 $outDir ..."
pip download -r $tmpFile `
    --platform manylinux2014_x86_64 `
    --python-version 310 `
    --only-binary=:all: `
    -d $outDir `
    -i https://pypi.tuna.tsinghua.edu.cn/simple

Remove-Item $tmpFile -ErrorAction SilentlyContinue

Write-Host "`n正在下载 jieba (源码包) ..."
pip download jieba -d $outDir --no-binary=:all: -i https://pypi.tuna.tsinghua.edu.cn/simple

Write-Host "`n正在专门下载 onnxruntime-gpu (CUDA 12.x) ..."
pip download onnxruntime-gpu `
    --platform manylinux2014_x86_64 `
    --python-version 310 `
    --only-binary=:all: `
    -d $outDir `
    -i https://pypi.tuna.tsinghua.edu.cn/simple `
    --extra-index-url https://aiinfra.pkgs.visualstudio.com/PublicPackages/_packaging/onnxruntime-cuda-12/pypi/simple/

Write-Host "`nDownload complete."
