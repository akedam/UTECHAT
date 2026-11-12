param([Parameter(Mandatory=$true)][string]$BackendUrl)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $repoRoot
try {
    & node (Join-Path $PSScriptRoot 'prepare-tester.js') $BackendUrl
    if ($LASTEXITCODE -ne 0) { throw 'Backend chua san sang. Chua build APK de chia se.' }
    $env:GRADLE_USER_HOME = Join-Path $repoRoot '.gradle-user-home'
    & .\gradlew.bat assembleRelease testDebugUnitTest --offline
    if ($LASTEXITCODE -ne 0) { throw 'Build khong thanh cong.' }
    $source = Join-Path $repoRoot 'app/build/outputs/apk/release/app-arm64-v8a-release.apk'
    $target = Join-Path $repoRoot 'build/UTEChat-test.apk'
    if (-not (Test-Path -LiteralPath $source)) { throw 'Khong tim thay APK da ky.' }
    Copy-Item -LiteralPath $source -Destination $target -Force
    Write-Host "APK cho dien thoai Android 8+ arm64: $target"
} finally { Pop-Location }
