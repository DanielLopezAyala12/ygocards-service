# Stop ygocards-service on Windows, running the same graceful shutdown path a container does.
#
# Windows has no SIGTERM. Stop-Process and "taskkill /F" call TerminateProcess, which ends the
# JVM without running a single shutdown hook: no graceful drain, no log line, nothing. A plain
# "taskkill" without /F does not work either, because a console application has no window to
# receive the close message.
#
# What does work is a Ctrl+C console event delivered to the process group of the console the
# JVM was launched in. The JVM translates it into the same shutdown-hook sequence that SIGTERM
# triggers on Linux, so the behaviour being demonstrated here is the real one.

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$pidFile      = "service.pid"
$logFile      = "service.log"
$graceSeconds = if ($env:STOP_GRACE_SECONDS) { [int]$env:STOP_GRACE_SECONDS } else { 30 }

if (-not (Test-Path $pidFile)) {
    Write-Output "[stop] no $pidFile, nothing to stop"
    exit 1
}

$ids        = Get-Content $pidFile
$consolePid = [int]$ids[0]
$jvmPid     = if ($ids.Count -gt 1) { [int]$ids[1] } else { 0 }

if (-not (Get-Process -Id $consolePid -ErrorAction SilentlyContinue)) {
    Write-Output "[stop] console pid $consolePid is not running, removing stale $pidFile"
    Remove-Item $pidFile
    exit 0
}

Add-Type -Language CSharp -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class ConsoleSignal {
  [DllImport("kernel32.dll", SetLastError=true)] public static extern bool AttachConsole(uint processId);
  [DllImport("kernel32.dll", SetLastError=true)] public static extern bool FreeConsole();
  [DllImport("kernel32.dll", SetLastError=true)] public static extern bool SetConsoleCtrlHandler(IntPtr handler, bool add);
  [DllImport("kernel32.dll", SetLastError=true)] public static extern bool GenerateConsoleCtrlEvent(uint ctrlEvent, uint processGroupId);
}
'@

Write-Output "[stop] sending Ctrl+C to console group $consolePid (jvm pid $jvmPid)"

[void][ConsoleSignal]::FreeConsole()
$attached = [ConsoleSignal]::AttachConsole([uint32]$consolePid)
if (-not $attached) {
    Write-Output "[stop] could not attach to the console, falling back to a forced stop"
    Write-Output "[stop] WARNING: a forced stop skips graceful shutdown"
    Stop-Process -Id $consolePid -Force
    Remove-Item $pidFile
    exit 1
}

# Ignore the event in this process so that signalling the group does not stop the script.
[void][ConsoleSignal]::SetConsoleCtrlHandler([IntPtr]::Zero, $true)
$sent = [ConsoleSignal]::GenerateConsoleCtrlEvent(0, 0)   # 0 = CTRL_C_EVENT, 0 = whole group

$stopped = $false
for ($i = 0; $i -lt $graceSeconds; $i++) {
    Start-Sleep -Seconds 1
    if (-not (Get-Process -Id $consolePid -ErrorAction SilentlyContinue)) { $stopped = $true; break }
}

[void][ConsoleSignal]::FreeConsole()
[void][ConsoleSignal]::SetConsoleCtrlHandler([IntPtr]::Zero, $false)

if ($stopped) {
    Remove-Item $pidFile -ErrorAction SilentlyContinue
    Write-Output "[stop] stopped gracefully (event delivered: $sent)"
    Write-Output "[stop] shutdown lines from ${logFile}:"
    Select-String -Path $logFile -Pattern "Graceful shutdown|Shutdown signal" | ForEach-Object { $_.Line }
    exit 0
}

Write-Output "[stop] still running after ${graceSeconds}s, forcing"
Stop-Process -Id $consolePid -Force
Remove-Item $pidFile -ErrorAction SilentlyContinue
exit 1
