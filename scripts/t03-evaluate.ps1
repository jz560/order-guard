param([ValidateSet('offline', 'deepseek')][string]$Mode = 'offline')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if ($Mode -eq 'deepseek' -and $env:T03_ALLOW_LIVE -ne 'true') {
        throw '真实批量评测需显式设置 T03_ALLOW_LIVE=true；整批最多 60 次模型请求，不自动重试。'
    }
    if ($Mode -eq 'deepseek' -and -not $env:DEEPSEEK_API_KEY -and (Test-Path .env)) {
        foreach ($line in Get-Content .env) {
            if ($line -match '^DEEPSEEK_API_KEY=(.*)$') {
                $env:DEEPSEEK_API_KEY = $Matches[1].Trim().Trim('"').Trim("'")
            }
        }
    }
    if ($Mode -eq 'deepseek' -and -not $env:DEEPSEEK_API_KEY) { throw '缺少本机 DEEPSEEK_API_KEY。' }
    & ./mvnw.cmd -B -ntp '-Dmaven.repo.local=.cache/m2' -pl backend -am compile
    if ($LASTEXITCODE -ne 0) { throw '编译失败' }
    & ./mvnw.cmd -B -ntp '-Dmaven.repo.local=.cache/m2' -pl backend org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath '-Dmdep.outputFile=target/t03-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw '生成运行路径失败' }
    $dependencies = (Get-Content -Raw backend/target/t03-classpath.txt).Trim()
    & "$env:JAVA_HOME/bin/java.exe" -cp "backend/target/classes;$dependencies" com.example.orderagent.backend.evaluation.PilotEvaluation $Mode
    if ($LASTEXITCODE -ne 0) { throw '评测中止，已有逐例结果保留在 .cache/t03-runs。' }
} finally { Pop-Location }
