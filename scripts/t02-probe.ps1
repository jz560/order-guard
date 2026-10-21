param([ValidateSet('stub', 'deepseek')][string]$Mode = 'stub')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if ($Mode -eq 'deepseek' -and $env:T02_ALLOW_LIVE -ne 'true') {
        throw '真实模型实验需要显式设置 T02_ALLOW_LIVE=true；每次最多 4 次请求，不自动重试。'
    }
    # 仅读取需要的一个键，不输出、不执行 .env 内容，也不覆盖已设置的环境变量。
    if ($Mode -eq 'deepseek' -and -not $env:DEEPSEEK_API_KEY -and (Test-Path .env)) {
        foreach ($line in Get-Content .env) {
            if ($line -match '^DEEPSEEK_API_KEY=(.*)$') {
                $env:DEEPSEEK_API_KEY = $Matches[1].Trim().Trim('"').Trim("'")
            }
        }
    }
    if ($Mode -eq 'deepseek' -and -not $env:DEEPSEEK_API_KEY) { throw '请先在环境变量或 .env 配置 DEEPSEEK_API_KEY。' }
    & ./mvnw.cmd -B -ntp '-Dmaven.repo.local=.cache/m2' -pl backend -am compile
    if ($LASTEXITCODE -ne 0) { throw '编译失败' }
    & ./mvnw.cmd -B -ntp '-Dmaven.repo.local=.cache/m2' -pl backend org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath '-Dmdep.outputFile=target/t02-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw '生成运行路径失败' }
    $dependencies = (Get-Content -Raw backend/target/t02-classpath.txt).Trim()
    & "$env:JAVA_HOME/bin/java.exe" -cp "backend/target/classes;$dependencies" com.example.orderagent.backend.diagnosis.T02Probe $Mode
    if ($LASTEXITCODE -ne 0) { throw '实验已停止；请查看记录中的终止原因。' }
} finally { Pop-Location }
