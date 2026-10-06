<#
.SYNOPSIS
  Launch an installed Nuvio Z desktop app with the stutter probe attached, for a human-driven session.

.DESCRIPTION
  The app runs from its own jpackage launcher and JDK 17 runtime, signed in as usual. The probe is
  injected with JAVA_TOOL_OPTIONS, set for this launch only. When the app is closed the script stops
  the counter log and prints where the results are; analyze.py turns them into a report.

  -Smoke runs a throwaway, signed-out copy instead: empty APPDATA/LOCALAPPDATA under the output
  directory and in-memory java.util.prefs, so neither the real data nor the shared registry login
  is touched. Use it to check the probe, never to measure.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File observe.ps1 -Jar C:\perf\stutterprobe.jar -Out C:\perf\run-1
#>
param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [Parameter(Mandatory = $true)][string]$Out,
    [string]$App = "C:\Program Files\Nuvio Z\Nuvio Z.exe",
    [switch]$Smoke,
    [switch]$NoShots,
    [int]$SmokeSeconds = 0
)
$ErrorActionPreference = 'Stop'

# JAVA_TOOL_OPTIONS is split on spaces; keep the agent's paths free of them.
foreach ($p in @($Jar, $Out)) {
    if ($p -match '\s') { throw "Path must not contain spaces: $p" }
}
if (-not (Test-Path $Jar)) { throw "Probe jar not found: $Jar" }
$running = Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -eq $App }
if ($running -and -not $Smoke) {
    throw "$App is already running (pid $($running.ProcessId -join ', ')). Close it first: one signed-in instance at a time."
}
New-Item -ItemType Directory -Force $Out | Out-Null

$agent = "-javaagent:$Jar=dir=$Out"
if ($NoShots) { $agent += ",shots=0" }
$opts = $agent
if ($Smoke) {
    $opts += ' -Djava.util.prefs.PreferencesFactory=StutterProbe$MemoryPrefsFactory'
    $env:APPDATA = Join-Path $Out 'smoke-appdata'
    $env:LOCALAPPDATA = Join-Path $Out 'smoke-localappdata'
    New-Item -ItemType Directory -Force $env:APPDATA, $env:LOCALAPPDATA | Out-Null
}
$env:JAVA_TOOL_OPTIONS = $opts

# Whole-machine and per-process CPU, once a second. GPU 3D engine utilisation per process.
$counters = @(
    '\Processor(_Total)\% Processor Time',
    '\Process(Nuvio Z*)\% Processor Time',
    '\Process(Nuvio Z*)\ID Process',
    '\Process(java*)\% Processor Time',
    '\GPU Engine(*engtype_3D)\Utilization Percentage'
)
$cpuCsv = Join-Path $Out 'counters.csv'
$tp = Start-Process -FilePath typeperf.exe -ArgumentList (($counters | ForEach-Object { '"' + $_ + '"' }) + @('-si', '1', '-f', 'CSV', '-o', ('"' + $cpuCsv + '"'), '-y')) -WindowStyle Hidden -PassThru

[System.IO.File]::WriteAllText((Join-Path $Out 'launched.txt'), [string][DateTimeOffset]::Now.ToUnixTimeMilliseconds())
$proc = Start-Process -FilePath $App -PassThru
Remove-Item Env:JAVA_TOOL_OPTIONS
Write-Host "Nuvio Z launched (launcher pid $($proc.Id)); recording to $Out"

if ($SmokeSeconds -gt 0) {
    Start-Sleep -Seconds $SmokeSeconds
    $kids = Get-CimInstance Win32_Process | Where-Object { $_.ParentProcessId -eq $proc.Id }
    foreach ($k in $kids) { (Get-Process -Id $k.ProcessId -ErrorAction SilentlyContinue).CloseMainWindow() | Out-Null }
    Start-Sleep -Seconds 5
}
$proc.WaitForExit()
Start-Sleep -Milliseconds 500
if (-not $tp.HasExited) { Stop-Process -Id $tp.Id -Force }
[System.IO.File]::WriteAllText((Join-Path $Out 'exited.txt'), [string][DateTimeOffset]::Now.ToUnixTimeMilliseconds())
Write-Host "App closed. Results: $Out"
