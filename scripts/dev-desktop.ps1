param(
    [ValidateSet("normal", "hot", "mcp")]
    [string]$Mode = "hot"
)

$ErrorActionPreference = "Stop"

# Gradle takes its project directory from the working directory, not from the path of the wrapper
# it was invoked through, so $PSScriptRoot finding gradlew.bat is not enough. A caller that starts
# this from somewhere else - Claude Code spawning the MCP server with the parent folder as its cwd -
# ran the build against a directory with no settings.gradle, which failed before the server could
# speak and surfaced only as CONNECTION_CLOSED.
Set-Location -LiteralPath (Join-Path $PSScriptRoot "..")

function Test-JavaHome([string]$Path) {
    return $Path -and (Test-Path -LiteralPath (Join-Path $Path "bin\java.exe"))
}

$javaHomes = @(
    $env:JAVA_HOME,
    "C:\Program Files\Android\Android Studio\jbr"
)

$jetBrainsRoot = "C:\Program Files\JetBrains"
if (Test-Path -LiteralPath $jetBrainsRoot) {
    $javaHomes += Get-ChildItem -LiteralPath $jetBrainsRoot -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName "jbr" }
}

$javaHome = $javaHomes | Where-Object { Test-JavaHome $_ } | Select-Object -First 1
if (-not $javaHome) {
    throw "No JDK/JBR was found. Set JAVA_HOME or install Android Studio/IntelliJ with its bundled JetBrains Runtime."
}

$env:JAVA_HOME = $javaHome
$env:Path = (Join-Path $javaHome "bin") + ";" + $env:Path

$gradleArgs = switch ($Mode) {
    "normal" { @(':composeApp:run', '--console=plain') }
    "hot" { @(':composeApp:hotRunDesktop', '--auto', '--console=plain') }
    "mcp" { @('--no-daemon', '--quiet', '--console=plain', ':composeApp:hotMcpServerDesktop') }
}

& (Join-Path $PSScriptRoot "..\gradlew.bat") @gradleArgs
exit $LASTEXITCODE
