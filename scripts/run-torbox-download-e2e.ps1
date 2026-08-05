param(
    [string]$Fixture = (Join-Path $PSScriptRoot '..\torbox-download-fixture.json')
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$fixturePath = [System.IO.Path]::GetFullPath($Fixture)

if (-not (Test-Path -LiteralPath $fixturePath -PathType Leaf)) {
    throw "TorBox fixture not found. Copy scripts/torbox-download-fixture.example.json to torbox-download-fixture.json and replace every placeholder with your local source metadata."
}

$secureApiKey = Read-Host 'TorBox API key (kept out of shell history)' -AsSecureString
$secretPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureApiKey)

try {
    $env:NUVIO_TORBOX_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($secretPointer)
    $env:NUVIO_TORBOX_TEST_SOURCES = $fixturePath

    & (Join-Path $repositoryRoot 'gradlew.bat') `
        :composeApp:desktopTest `
        --tests '*DesktopDownloadQueueE2ETest.real TorBox season rechecks and remints every source' `
        --console=plain `
        --no-daemon `
        --rerun-tasks `
        --no-configuration-cache

    if ($LASTEXITCODE -ne 0) {
        throw "TorBox download E2E test failed with exit code $LASTEXITCODE."
    }
} finally {
    Remove-Item Env:NUVIO_TORBOX_API_KEY -ErrorAction SilentlyContinue
    Remove-Item Env:NUVIO_TORBOX_TEST_SOURCES -ErrorAction SilentlyContinue
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secretPointer)
}
