# RepoPilot 后端启动脚本
# 用法: .\run.ps1

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

Write-Host "正在编译..." -ForegroundColor Cyan
mvn -q compile
if ($LASTEXITCODE -ne 0) {
    Write-Host "编译失败，请检查上方错误信息。" -ForegroundColor Red
    exit 1
}

Write-Host "正在生成依赖 classpath..." -ForegroundColor Cyan
mvn -q dependency:build-classpath "-Dmdep.outputFile=cp.txt"
if ($LASTEXITCODE -ne 0) {
    Write-Host "生成 classpath 失败。" -ForegroundColor Red
    exit 1
}

$deps = (Get-Content "cp.txt" -Raw).Trim()
$cp = "target/classes;$deps"

Write-Host "启动后端 (http://localhost:8000) ..." -ForegroundColor Green
Write-Host "按 Ctrl+C 停止服务" -ForegroundColor DarkGray
java -cp $cp com.repopilot.RepoPilotApplication
