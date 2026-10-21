$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    & ./mvnw.cmd -B -ntp verify
    if ($LASTEXITCODE -ne 0) { throw '后端完整验证失败' }
    Push-Location frontend
    try {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败' }
        foreach ($check in @('lint', 'typecheck', 'test:unit', 'build')) {
            & npm.cmd run $check
            if ($LASTEXITCODE -ne 0) { throw "前端检查失败：$check" }
        }
    } finally { Pop-Location }
} finally { Pop-Location }
