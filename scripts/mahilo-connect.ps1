# Mở CLI nối vào một agent trên Windows (PowerShell), giống `mahilo connect --agent-name X` của bản Python.
#
#   powershell -ExecutionPolicy Bypass -File scripts\mahilo-connect.ps1 --agent-name SalesAgent [--url http://localhost:8000] [--json]
#
# Lần đầu sẽ gọi sbt để build module cli và lưu classpath vào target\cli-classpath.txt; các lần sau chạy
# thẳng bằng java (nhanh, và mở được nhiều cửa sổ cùng lúc mà không tranh nhau khoá của sbt).
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$CpFile = Join-Path $Root "target\cli-classpath.txt"

# Cho terminal hiện đúng tiếng Việt (UTF-8)
chcp 65001 > $null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding = [System.Text.Encoding]::UTF8

if (-not (Test-Path $CpFile) -or (Get-Item $CpFile).Length -eq 0 -or $env:MAHILO_CLI_REBUILD) {
  Write-Host "Đang build CLI (chỉ lần đầu)..."
  New-Item -ItemType Directory -Force -Path (Join-Path $Root "target") > $null
  Push-Location $Root
  try { $out = & sbt -batch -error "export cli/Runtime/fullClasspath" } finally { Pop-Location }
  ($out | Select-Object -Last 1).Trim() | Set-Content -Encoding ASCII $CpFile
}

$Cp = (Get-Content $CpFile -Raw).Trim()
& java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" "-Dstdin.encoding=UTF-8" -cp $Cp mahilo.cli.MahiloCli connect @args
