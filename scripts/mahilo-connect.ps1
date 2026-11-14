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

# Classpath hợp lệ phải có thư mục classes của module cli; file hỏng (do lần build trước lỗi) thì build lại.
function Test-Classpath($path) {
  if (-not (Test-Path $path)) { return $false }
  $c = (Get-Content $path -Raw)
  return ($c -and $c -match 'modules[\\/]cli[\\/]target')
}

if (-not (Test-Classpath $CpFile) -or $env:MAHILO_CLI_REBUILD) {
  Write-Host "Đang build CLI (chỉ lần đầu, khoảng 1 phút)..."
  New-Item -ItemType Directory -Force -Path (Join-Path $Root "target") > $null
  Remove-Item -Force -ErrorAction SilentlyContinue $CpFile
  Push-Location $Root
  # sbt.server.forcestart: cho phép chạy sbt thứ hai khi cửa sổ server (sbt bootstrap/run) đang giữ khoá sbt.
  try { $out = & sbt "-Dsbt.server.forcestart=true" -batch -error "export cli/Runtime/fullClasspath"; $code = $LASTEXITCODE } finally { Pop-Location }
  $cp = ($out | Where-Object { $_ -match 'modules[\\/]cli[\\/]target' } | Select-Object -Last 1)
  if ($code -ne 0 -or -not $cp) {
    $out | Write-Host
    throw "Build CLI thất bại (sbt exit $code). Xem log ở trên."
  }
  $cp.Trim() | Set-Content -Encoding ASCII $CpFile
}

$Cp = (Get-Content $CpFile -Raw).Trim()
& java "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" "-Dstdin.encoding=UTF-8" -cp $Cp mahilo.cli.MahiloCli connect @args
