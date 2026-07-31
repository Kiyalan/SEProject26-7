# Download and unpack Apache JMeter into perf/tools if missing.
param(
    [string]$Version = "5.6.3",
    [string]$ToolsDir = ""
)

$ErrorActionPreference = "Stop"
$PerfRoot = Split-Path -Parent $PSScriptRoot
if (-not $ToolsDir) {
    $ToolsDir = Join-Path $PerfRoot "tools"
}

$TargetDir = Join-Path $ToolsDir "apache-jmeter-$Version"
$JMeterBat = Join-Path $TargetDir "bin\jmeter.bat"
if (Test-Path $JMeterBat) {
    Write-Host "JMeter already present: $JMeterBat" -ForegroundColor Green
    Write-Output $JMeterBat
    exit 0
}

New-Item -ItemType Directory -Force -Path $ToolsDir | Out-Null
$ZipName = "apache-jmeter-$Version.zip"
$ZipPath = Join-Path $ToolsDir $ZipName
$Url = "https://archive.apache.org/dist/jmeter/binaries/$ZipName"

Write-Host "Downloading Apache JMeter $Version ..." -ForegroundColor Cyan
Write-Host $Url
Invoke-WebRequest -Uri $Url -OutFile $ZipPath

Write-Host "Extracting ..." -ForegroundColor Cyan
Expand-Archive -Path $ZipPath -DestinationPath $ToolsDir -Force
Remove-Item $ZipPath -Force

if (-not (Test-Path $JMeterBat)) {
    throw "JMeter binary not found after extract: $JMeterBat"
}

Write-Host "Installed: $JMeterBat" -ForegroundColor Green
Write-Output $JMeterBat
