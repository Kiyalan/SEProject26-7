# Kill listeners on ports 8000 and 5173
$ports = @(8000, 5173)
foreach ($port in $ports) {
    $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    foreach ($c in $conns) {
        $procId = $c.OwningProcess
        if ($procId -and $procId -gt 0) {
            $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
            Write-Host "Stopping port $port PID=$procId ($($proc.ProcessName))"
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
        }
    }
}
Write-Host ""
Write-Host "Ports cleared. Run in two terminals:"
Write-Host "  npm run dev:backend"
Write-Host "  npm run dev"
Write-Host ""
Write-Host "Verify backend: http://localhost:8000/api/health"
Write-Host "  Check pid, startedAt, llmConfigured"
