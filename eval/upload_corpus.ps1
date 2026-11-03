# 上传语料到知识库，并等待索引完成
# 用法：先启动应用，然后在项目根目录执行  .\eval\upload_corpus.ps1
$ErrorActionPreference = "Stop"

$base = "http://localhost:8080"
$corpus = Join-Path $PSScriptRoot "..\test-docs\corpus"
$kbId = 1

# 1. 健康检查
try {
    $health = Invoke-RestMethod "$base/api/health"
    Write-Host "服务正常：db=$($health.db)"
} catch {
    Write-Host "服务未启动，请先运行 mvn spring-boot:run" -ForegroundColor Red
    exit 1
}

# 2. 逐个上传
$files = Get-ChildItem $corpus -Filter *.txt | Sort-Object Name
if ($files.Count -eq 0) {
    Write-Host "corpus 目录为空：$corpus" -ForegroundColor Red
    exit 1
}

$ids = @()
foreach ($file in $files) {
    Write-Host "上传 $($file.Name) ..."
    $response = & curl.exe -sS -X POST "$base/api/documents" -F "kbId=$kbId" -F "file=@$($file.FullName)"
    $doc = $response | ConvertFrom-Json
    $ids += $doc.id
    Write-Host "  -> docId=$($doc.id), status=$($doc.status)"
}

# 3. 轮询直到全部就绪（最多 60 秒）
Write-Host "等待索引完成..."
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 2
    $allReady = $true
    foreach ($id in $ids) {
        $status = Invoke-RestMethod "$base/api/documents/$id/status"
        if ($status.data.status -ne 2 -and $status.data.status -ne 3) {
            $allReady = $false
            break
        }
    }
    if ($allReady) { break }
}

# 4. 汇总
foreach ($id in $ids) {
    $status = Invoke-RestMethod "$base/api/documents/$id/status"
    $d = $status.data
    Write-Host ("docId={0} file={1} status={2} chunks={3}" -f $d.id, $d.file_name, $d.status, $d.chunk_count)
}
Write-Host "完成。下一步：导入 eval/load_eval.sql 并运行评测脚本。"
