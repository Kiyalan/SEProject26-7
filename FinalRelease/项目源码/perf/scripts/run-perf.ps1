<#
.SYNOPSIS
  Run RepoPilot JMeter performance suite (bypass frontend OAuth UI).

.DESCRIPTION
  Simulates 1 and 10 virtual users against TinyTestRepo and SEProject26-7 via
  Spring Boot APIs (http://localhost:8000). Produces JMeter HTML reports,
  response-time curves, and a comparison markdown/json summary.

.EXAMPLE
  .\perf\scripts\run-perf.ps1
  .\perf\scripts\run-perf.ps1 -IncludeChat:$false -Loops 3
#>
param(
    [string]$BaseUrl = "",
    [string]$JwtToken = "",
    [int]$Loops = -1,
    [switch]$IncludeChat,
    [switch]$SkipDownload,
    [switch]$SkipCompare,
    [string]$JMeterHome = "",
    [string]$RunId = ""
)

$ErrorActionPreference = "Stop"
$PerfRoot = Split-Path -Parent $PSScriptRoot
$RepoRoot = Split-Path -Parent $PerfRoot

function Import-DotEnv([string]$Path) {
    if (-not (Test-Path $Path)) { return @{} }
    $map = @{}
    Get-Content -Path $Path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#") -or -not $line.Contains("=")) { return }
        $idx = $line.IndexOf("=")
        $key = $line.Substring(0, $idx).Trim()
        $val = $line.Substring($idx + 1).Trim().Trim('"').Trim("'")
        $map[$key] = $val
    }
    return $map
}

$envMap = @{}
foreach ($candidate in @(
        (Join-Path $PerfRoot ".env"),
        (Join-Path $RepoRoot "backend\.env"),
        (Join-Path $RepoRoot ".env")
    )) {
    $loaded = Import-DotEnv $candidate
    if ($loaded.Count -gt 0) {
        foreach ($k in $loaded.Keys) {
            if (-not $envMap.ContainsKey($k)) { $envMap[$k] = $loaded[$k] }
        }
    }
}

if (-not $BaseUrl) {
    $BaseUrl = if ($envMap["BASE_URL"]) { $envMap["BASE_URL"] } else { "http://localhost:8000" }
}
$BaseUrl = $BaseUrl.TrimEnd("/")
$uri = [Uri]$BaseUrl
$protocol = $uri.Scheme
$hostName = $uri.Host
$port = if ($uri.IsDefaultPort) {
    if ($protocol -eq "https") { "443" } else { "8000" }
} else {
    [string]$uri.Port
}
# When user passes http://localhost without port, Uri.IsDefaultPort is true for http→80;
# RepoPilot backend defaults to 8000, so keep 8000 unless an explicit port was provided.
if ($uri.IsDefaultPort -and $protocol -eq "http") {
    $port = "8000"
}

if ($Loops -lt 0) {
    $Loops = if ($envMap["LOOPS"]) { [int]$envMap["LOOPS"] } else { 5 }
}

$ramp1 = if ($envMap["RAMP_UP_1"]) { [int]$envMap["RAMP_UP_1"] } else { 1 }
$ramp10 = if ($envMap["RAMP_UP_10"]) { [int]$envMap["RAMP_UP_10"] } else { 5 }
$timeoutMs = if ($envMap["HTTP_TIMEOUT_MS"]) { [int]$envMap["HTTP_TIMEOUT_MS"] } else { 120000 }

if (-not $PSBoundParameters.ContainsKey("IncludeChat")) {
    $includeChatValue = if ($envMap.ContainsKey("INCLUDE_CHAT")) { $envMap["INCLUDE_CHAT"] } else { "true" }
    $IncludeChat = ($includeChatValue -match '^(1|true|yes)$')
} 

if (-not $JwtToken) {
    $JwtToken = if ($env:JWT_TOKEN) { $env:JWT_TOKEN } elseif ($envMap["JWT_TOKEN"]) { $envMap["JWT_TOKEN"] } else { "" }
}

if (-not $JwtToken) {
    Write-Host "Minting JWT (bypass frontend OAuth) ..." -ForegroundColor Cyan
    $mintArgs = @((Join-Path $PSScriptRoot "mint-jwt.py"))
    if ($envMap["GITHUB_USERNAME"]) { $env:GITHUB_USERNAME = $envMap["GITHUB_USERNAME"] }
    if ($envMap["GITHUB_TOKEN"]) { $env:GITHUB_TOKEN = $envMap["GITHUB_TOKEN"] }
    if ($envMap["JWT_SECRET"] -ne $null) { $env:JWT_SECRET = $envMap["JWT_SECRET"] }
    $JwtToken = (& python @mintArgs).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $JwtToken) {
        throw "Failed to mint JWT. Configure perf/.env from perf/env.example (GITHUB_USERNAME, GITHUB_TOKEN, JWT_SECRET)."
    }
}

Write-Host "Checking backend at $BaseUrl ..." -ForegroundColor Cyan
try {
    $null = Invoke-WebRequest -Uri "$BaseUrl/auth/github" -MaximumRedirection 0 -TimeoutSec 5 -ErrorAction Stop
} catch {
    if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 302) {
        # expected redirect
    } elseif ($_.Exception.Response) {
        Write-Warning "Backend responded with status $([int]$_.Exception.Response.StatusCode)"
    } else {
        throw "Backend not reachable at $BaseUrl. Start it first (backend\run.ps1)."
    }
}

# Resolve JMeter (prefer ApacheJMeter.jar to avoid jmeter.bat "Press any key" on errors)
function Resolve-JMeterJar([string]$HomeDir) {
    if (-not $HomeDir) { return "" }
    $jar = Join-Path $HomeDir "bin\ApacheJMeter.jar"
    if (Test-Path $jar) { return $jar }
    return ""
}

$jmeterJar = ""
if ($JMeterHome) {
    $jmeterJar = Resolve-JMeterJar $JMeterHome
} elseif ($env:JMETER_HOME) {
    $jmeterJar = Resolve-JMeterJar $env:JMETER_HOME
}

if (-not $jmeterJar) {
    if ($SkipDownload) {
        throw "JMeter not found. Set JMETER_HOME or omit -SkipDownload."
    }
    Write-Host "Ensuring Apache JMeter is available ..." -ForegroundColor Cyan
    $jmeterBat = & (Join-Path $PSScriptRoot "download-jmeter.ps1")
    $jmeterHomeResolved = Split-Path -Parent (Split-Path -Parent $jmeterBat)
    $jmeterJar = Resolve-JMeterJar $jmeterHomeResolved
    if (-not (Test-Path $jmeterJar)) {
        throw "JMeter download failed or ApacheJMeter.jar missing."
    }
}

if (-not $RunId) {
    $RunId = Get-Date -Format "yyyyMMdd-HHmmss"
}
$runDir = Join-Path $PerfRoot "results\$RunId"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$plan = Join-Path $PerfRoot "plans\repopilot-api-load.jmx"
$queryCsv = Join-Path $PerfRoot "data\queries.csv"

$scenarios = @(
    @{ Name = "TinyTestRepo-1vu";  Repo = "Kiyalan/TinyTestRepo";  RepoId = "1316984338"; Threads = 1;  RampUp = $ramp1 },
    @{ Name = "TinyTestRepo-10vu"; Repo = "Kiyalan/TinyTestRepo";  RepoId = "1316984338"; Threads = 10; RampUp = $ramp10 },
    @{ Name = "SEProject26-7-1vu";  Repo = "Kiyalan/SEProject26-7"; RepoId = "1291674343"; Threads = 1;  RampUp = $ramp1 },
    @{ Name = "SEProject26-7-10vu"; Repo = "Kiyalan/SEProject26-7"; RepoId = "1291674343"; Threads = 10; RampUp = $ramp10 }
)

$includeChatStr = if ($IncludeChat) { "true" } else { "false" }

Write-Host ""
Write-Host "RepoPilot performance run: $RunId" -ForegroundColor Green
Write-Host "  BaseUrl      = $BaseUrl"
Write-Host "  Loops        = $Loops"
Write-Host "  IncludeChat  = $includeChatStr"
Write-Host "  JMeter       = $jmeterJar"
Write-Host "  Output       = $runDir"
Write-Host ""

foreach ($s in $scenarios) {
    $outDir = Join-Path $runDir $s.Name
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    $jtl = Join-Path $outDir "results.jtl"
    $html = Join-Path $outDir "html-report"
    if (Test-Path $html) { Remove-Item -Recurse -Force $html }

    $meta = @{
        name       = $s.Name
        repo       = $s.Repo
        repo_id    = $s.RepoId
        threads    = $s.Threads
        ramp_up    = $s.RampUp
        loops      = $Loops
        include_chat = $IncludeChat
        base_url   = $BaseUrl
        url        = "https://github.com/$($s.Repo)"
    } | ConvertTo-Json
    # utf8NoBOM avoids Python json.loads UTF-8 BOM errors on Windows PowerShell 5
    $scenarioPath = Join-Path $outDir "scenario.json"
    [System.IO.File]::WriteAllText($scenarioPath, $meta, [System.Text.UTF8Encoding]::new($false))

    Write-Host ">>> Running $($s.Name) (users=$($s.Threads), repo=$($s.RepoId)) ..." -ForegroundColor Cyan

    # Write props to a file (avoids PowerShell splitting values like 127.0.0.1)
    $propsPath = Join-Path $outDir "jmeter.properties"
    $queryCsvProp = ($queryCsv -replace '\\', '/')
    @(
        "PROTOCOL=$protocol"
        "HOST=$hostName"
        "PORT=$port"
        "JWT_TOKEN=$JwtToken"
        "REPO_ID=$($s.RepoId)"
        "THREADS=$($s.Threads)"
        "RAMP_UP=$($s.RampUp)"
        "LOOPS=$Loops"
        "INCLUDE_CHAT=$includeChatStr"
        "HTTP_TIMEOUT_MS=$timeoutMs"
        "QUERY_CSV=$queryCsvProp"
    ) | Set-Content -Path $propsPath -Encoding ASCII

    $proc = Start-Process -FilePath "java" -ArgumentList @(
        "-jar", $jmeterJar,
        "-n",
        "-t", $plan,
        "-l", $jtl,
        "-e",
        "-o", $html,
        "-q", $propsPath
    ) -Wait -PassThru -NoNewWindow
    $jmExit = $proc.ExitCode
    if ($jmExit -ne 0) {
        Write-Warning "JMeter exited with code $jmExit for $($s.Name)"
    } else {
        Write-Host "    HTML report: $html\index.html" -ForegroundColor DarkGray
    }
}

if (-not $SkipCompare) {
    Write-Host ""
    Write-Host "Generating comparison report & charts ..." -ForegroundColor Cyan
    & python (Join-Path $PSScriptRoot "compare-results.py") --run-dir $runDir
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "compare-results.py failed (exit $LASTEXITCODE)"
    }
}

Write-Host ""
Write-Host "Done. Open:" -ForegroundColor Green
Write-Host "  $runDir\COMPARISON.md"
Write-Host "  $runDir\charts\comparison.png"
Write-Host "  $runDir\*\html-report\index.html"
Write-Output $runDir
