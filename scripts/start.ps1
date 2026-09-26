# Start ygocards-service in the background on Windows.
#
# Two things differ from the shell version, both because Windows has no SIGTERM.
#
# 1. The JVM is launched inside its own console, through cmd.exe. That console is what
#    stop.ps1 later attaches to in order to deliver a Ctrl+C event, which is the closest
#    Windows equivalent of SIGTERM and the only mechanism that runs the JVM shutdown hooks.
#    Launching the JVM directly, or from Git Bash, leaves no console to signal, and the
#    process can then only be killed outright, which skips graceful shutdown entirely.
#
# 2. The console process id is recorded next to the JVM process id, because the signal is
#    delivered to the console process group rather than to the JVM.

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$jar     = "target\ygocards-service.jar"
$pidFile = "service.pid"
$logFile = "service.log"

if (Test-Path $pidFile) {
    $existing = (Get-Content $pidFile | Select-Object -First 1)
    if (Get-Process -Id $existing -ErrorAction SilentlyContinue) {
        Write-Output "[start] already running with pid $existing"
        exit 1
    }
    Remove-Item $pidFile
}

if (Test-Path ".env") {
    Get-Content ".env" | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
            $key, $value = $line.Split("=", 2)
            Set-Item -Path "env:$($key.Trim())" -Value $value.Trim()
        }
    }
    Write-Output "[start] environment loaded from .env"
} else {
    Write-Output "[start] no .env present, using the defaults in application.properties"
}

if (-not (Test-Path $jar)) {
    Write-Output "[start] $jar not found, building"
    & .\mvnw.cmd -B -DskipTests package
}

$console = Start-Process -FilePath "cmd.exe" `
    -ArgumentList "/c", "java -jar $jar > $logFile 2>&1" `
    -PassThru

# Wait for the JVM to appear and claim the port.
$port = if ($env:PORT) { $env:PORT } else { "8080" }
$java = $null
for ($i = 0; $i -lt 60; $i++) {
    try {
        Invoke-WebRequest -Uri "http://localhost:$port/health" -UseBasicParsing -TimeoutSec 2 | Out-Null
        $java = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
                Where-Object { $_.ParentProcessId -eq $console.Id }
        break
    } catch { Start-Sleep -Milliseconds 500 }
}

if (-not $java) {
    Write-Output "[start] service did not answer on port $port, see $logFile"
    exit 1
}

# Line 1: console process id, the signal target. Line 2: JVM process id, for reporting.
Set-Content -Path $pidFile -Value @($console.Id, $java.ProcessId) -Encoding ascii

Write-Output "[start] console pid $($console.Id), jvm pid $($java.ProcessId), port $port, log $logFile"
Write-Output "[start] liveness:  curl http://localhost:$port/health"
Write-Output "[start] upstream:  curl http://localhost:$port/health/upstream"
