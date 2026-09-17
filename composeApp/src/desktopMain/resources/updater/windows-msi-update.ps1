# Installs a downloaded Nuvio Z MSI after the app has closed, then starts the app again.
#
# Started by AppUpdaterPlatform through run-hidden.js, with its inputs in the
# environment so no path has to survive Windows command-line quoting:
#
#   NUVIO_UPDATE_MSI            the downloaded MSI
#   NUVIO_UPDATE_LOG            where msiexec writes its verbose log
#   NUVIO_UPDATE_WAIT_PIDS      comma-separated processes to outlive (the JVM and its launcher)
#   NUVIO_UPDATE_RELAUNCH       the app launcher to start afterwards; may be empty
#   NUVIO_UPDATE_INSTALL_DIR    the folder the app is installed in now; may be empty
#   NUVIO_UPDATE_TITLE          caption for the failure message
#   NUVIO_UPDATE_FAILED_MESSAGE body of the failure message

$ErrorActionPreference = 'Continue'

$msi = $env:NUVIO_UPDATE_MSI
$log = $env:NUVIO_UPDATE_LOG
$relaunch = $env:NUVIO_UPDATE_RELAUNCH

# The installer replaces files the running app holds open; starting it first would
# only get the Restart Manager asking the user to close an app that is closing anyway.
foreach ($id in ($env:NUVIO_UPDATE_WAIT_PIDS -split ',')) {
    if ($id -match '^\d+$') {
        Wait-Process -Id ([int]$id) -Timeout 60 -ErrorAction SilentlyContinue
    }
}

# The MSI installs per machine, so msiexec needs elevation. /qn cannot ask for it and
# fails with 1625 when started unelevated, so elevation is requested here, once, and
# msiexec itself runs with the progress bar only: no dialogs, nothing to click.
$exitCode = 0
try {
    $arguments = @('/i', "`"$msi`"", '/passive', '/norestart', '/l*v', "`"$log`"")
    # The MSI would otherwise upgrade into Program Files and delete a custom-folder install.
    if ($env:NUVIO_UPDATE_INSTALL_DIR) {
        $arguments += "INSTALLDIR=`"$($env:NUVIO_UPDATE_INSTALL_DIR)`""
    }
    $process = Start-Process -FilePath 'msiexec.exe' -ArgumentList $arguments -Verb RunAs -Wait -PassThru -ErrorAction Stop
    $exitCode = $process.ExitCode
} catch {
    # Declining the UAC prompt lands here. That is a cancel, not a failure.
    $exitCode = 1602
}

# 3010 is success with a reboot pending; 1602 and 1223 are the user cancelling.
if ($exitCode -notin @(0, 3010, 1602, 1223)) {
    $message = "$($env:NUVIO_UPDATE_FAILED_MESSAGE) (msiexec $exitCode)`n`n$log"
    (New-Object -ComObject WScript.Shell).Popup($message, 0, $env:NUVIO_UPDATE_TITLE, 0x10) | Out-Null
}

# Relaunch whatever is installed now - the update, or the untouched old version when
# the install was cancelled or failed - so the user is never left with no app open.
if ($relaunch -and (Test-Path -LiteralPath $relaunch)) {
    Start-Process -FilePath $relaunch
}
