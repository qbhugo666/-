# 一键下载识别模型到 app/src/main/assets（模型较大，不入公开仓库）
# 用法：powershell -ExecutionPolicy Bypass -File scripts\download_models.ps1
# 若官方地址变更，请到 https://github.com/k2-fsa/sherpa-onnx/releases
# （tag: asr-models）手动下载对应文件放入 app/src/main/assets/

$ErrorActionPreference = 'Stop'
$dest = Join-Path $PSScriptRoot '..\app\src\main\assets'
$base = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models'

$files = @(
    @{ url = "$base/sensevoice.int8.onnx";      name = 'sensevoice.int8.onnx' },
    @{ url = "$base/sensevoice_tokens.txt";     name = 'tokens.txt' },
    @{ url = "$base/silero_vad.onnx";           name = 'silero_vad.onnx' }
)

New-Item -ItemType Directory -Force -Path $dest | Out-Null

foreach ($f in $files) {
    $target = Join-Path $dest $f.name
    if (Test-Path $target) {
        Write-Output "已存在，跳过：$($f.name)"
        continue
    }
    Write-Output "下载 $($f.name) ..."
    Invoke-WebRequest -Uri $f.url -OutFile $target -UseBasicParsing
    Write-Output "完成：$($f.name) ($([math]::Round((Get-Item $target).Length / 1MB, 1)) MB)"
}

Write-Output '全部模型就绪，可以构建了。'
