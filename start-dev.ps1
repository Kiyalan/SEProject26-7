# Start CodeWiki + backend + frontend for local development.
# Usage: .\start-dev.ps1

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

Write-Host "Starting postgres + codewiki..." -ForegroundColor Cyan
docker compose up -d postgres codewiki
if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker Compose failed. Install/start Docker Desktop, then retry." -ForegroundColor Red
    exit 1
}

$deadline = (Get-Date).AddMinutes(3)
do {
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/health" -TimeoutSec 2
        if ($health.status -eq "ok") { break }
    } catch {
        Start-Sleep -Seconds 3
    }
} while ((Get-Date) -lt $deadline)

try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:8001/api/health" -TimeoutSec 3
    if ($health.status -ne "ok") { throw "unexpected status" }
    Write-Host "CodeWiki is healthy." -ForegroundColor Green
} catch {
    Write-Host "CodeWiki did not become healthy in time. Check: docker compose logs -f codewiki" -ForegroundColor Red
    exit 1
}

New-Item -ItemType Directory -Force -Path "backend\data\repos" | Out-Null

Write-Host "Starting backend in a new window..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command", "Set-Location '$PSScriptRoot\backend'; .\run.ps1"

Write-Host "Starting frontend in a new window..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command", "Set-Location '$PSScriptRoot\frontend'; if (-not (Test-Path node_modules)) { npm install }; npm run dev"

Write-Host ""
Write-Host "Frontend: http://localhost:5173" -ForegroundColor Green
Write-Host "Backend:  http://localhost:8000" -ForegroundColor Green
Write-Host "CodeWiki: http://127.0.0.1:8001" -ForegroundColor Green
