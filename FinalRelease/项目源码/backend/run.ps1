# RepoPilot backend launcher
# Usage: .\run.ps1
# Stops previous RepoPilot Java processes, rebuilds, then starts the latest jar.

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

function Stop-ExistingBackend {
    $killed = @{}

    $listeners = netstat -ano | Select-String ':8000\s+.*LISTENING'
    foreach ($line in $listeners) {
        $processId = ($line -split '\s+')[-1]
        if ($processId -match '^\d+$') {
            $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue
            if ($proc -and ($proc.CommandLine -match 'repopilot|RepoPilotApplication|repopilot-backend-1\.0\.0\.jar')) {
                Write-Host "Stopping port-8000 backend PID=$processId ..." -ForegroundColor Yellow
                Stop-Process -Id ([int]$processId) -Force -ErrorAction SilentlyContinue
                $killed[$processId] = $true
            }
        }
    }

    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_.CommandLine -match 'repopilot-backend|RepoPilotApplication|repopilot-backend-1\.0\.0\.jar') {
            $pidText = [string]$_.ProcessId
            if (-not $killed.ContainsKey($pidText)) {
                Write-Host "Stopping stale backend PID=$pidText ..." -ForegroundColor Yellow
                Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
                $killed[$pidText] = $true
            }
        }
    }

    # Wait until the jar is unlocked (common Windows rename failure during mvnw repackage).
    $jar = Join-Path $PSScriptRoot "target\repopilot-backend-1.0.0.jar"
    $deadline = (Get-Date).AddSeconds(20)
    while ((Test-Path $jar) -and ((Get-Date) -lt $deadline)) {
        try {
            $stream = [System.IO.File]::Open($jar, 'Open', 'ReadWrite', 'None')
            $stream.Close()
            break
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    Start-Sleep -Seconds 1
}

Stop-ExistingBackend

New-Item -ItemType Directory -Force -Path "data\repos" | Out-Null

$codeWikiUrl = if ($env:CODEWIKI_BASE_URL) { $env:CODEWIKI_BASE_URL.TrimEnd('/') } else { "http://127.0.0.1:8001" }
try {
    $health = Invoke-RestMethod -Uri "$codeWikiUrl/api/health" -TimeoutSec 3
    if ($health.status -ne "ok") {
        Write-Warning "CodeWiki health check returned an unexpected status."
    } else {
        Write-Host "CodeWiki OK at $codeWikiUrl" -ForegroundColor Green
    }
} catch {
    Write-Warning "CodeWiki is not reachable at $codeWikiUrl. Knowledge build/search will fail until you run: docker compose up -d postgres codewiki"
}

Write-Host "Building backend package (Maven Wrapper)..." -ForegroundColor Cyan
$mvnw = Join-Path $PSScriptRoot "mvnw.cmd"
if (-not (Test-Path $mvnw)) {
    Write-Host "Maven Wrapper not found: $mvnw" -ForegroundColor Red
    exit 1
}
& $mvnw -q package "-DskipTests"
if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed. If the jar is locked, stop other Java processes and retry." -ForegroundColor Red
    exit 1
}

Write-Host "Starting backend at http://localhost:8000 ..." -ForegroundColor Green
Write-Host "Press Ctrl+C to stop" -ForegroundColor DarkGray
java -jar "target/repopilot-backend-1.0.0.jar"
