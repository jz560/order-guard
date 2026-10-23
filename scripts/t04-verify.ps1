$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    # 必须从根聚合构建：模拟服务的进程实验使用本轮刚打包的两份应用 JAR。
    & ./mvnw.cmd -B -ntp '-Dmaven.repo.local=.cache/m2' '-Dit.test=T04RecoveryIT' '-Dfailsafe.failIfNoSpecifiedTests=false' verify
    if ($LASTEXITCODE -ne 0) { throw 'T04 验证失败，检查 Failsafe 报告和临时实验记录。' }
    $evidenceDirectory = (Get-Content -Raw simulator/target/t04-latest.txt).Trim()
    Write-Output "T04 验证完成，实验记录：$evidenceDirectory"
} finally { Pop-Location }
