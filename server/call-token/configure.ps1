param(
    [string]$AgoraAppId,
    [string]$FirebaseCredentialsPath,
    [string]$TokenServerUrl = 'http://10.0.2.2:8787/api/rtc-token'
)
$ErrorActionPreference = 'Stop'
if (-not $AgoraAppId) { $AgoraAppId = Read-Host 'Agora App ID (Manage credentials trong Agora Console)' }
if ($AgoraAppId -notmatch '^[a-fA-F0-9]{32}$') { throw 'App ID phai la 32 ky tu hex tu du an Agora cua ban.' }
$secret = Read-Host 'Agora Primary Certificate (chi luu o token server)' -AsSecureString
$plainCertificate = [System.Net.NetworkCredential]::new('', $secret).Password
if ($plainCertificate -notmatch '^[a-fA-F0-9]{32}$') { throw 'Certificate phai la 32 ky tu hex. Khong dung temp token.' }
if (-not $FirebaseCredentialsPath) { $FirebaseCredentialsPath = Read-Host 'Duong dan file Firebase Admin service-account JSON (giu ngoai repo)' }
$credentials = (Resolve-Path -LiteralPath $FirebaseCredentialsPath).Path
$credentialData = Get-Content -LiteralPath $credentials -Raw | ConvertFrom-Json
if ($credentialData.type -ne 'service_account' -or $credentialData.project_id -ne 'utechat-ab9d8') {
    throw 'Can service account cua Firebase project utechat-ab9d8.'
}
$endpoint = [Uri]$TokenServerUrl
if (-not $endpoint.IsAbsoluteUri -or ($endpoint.Scheme -ne 'https' -and
        -not ($endpoint.Scheme -eq 'http' -and $endpoint.Host -in '10.0.2.2','localhost'))) {
    throw 'Dien thoai that can URL HTTPS. Emulator debug ho tro http://10.0.2.2:8787/api/rtc-token.'
}
if ($endpoint.AbsolutePath -ne '/api/rtc-token') { throw 'URL phai ket thuc bang /api/rtc-token.' }
$envPath = Join-Path $PSScriptRoot '.env'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$clientPath = Join-Path $repoRoot 'agora.local.properties'
if ((Test-Path -LiteralPath $envPath) -or (Test-Path -LiteralPath $clientPath)) {
    $overwrite = Read-Host 'Da co cau hinh local. Go YES de thay the'
    if ($overwrite -cne 'YES') { throw 'Khong thay doi cau hinh hien co.' }
}
$credentialEnv = $credentials.Replace('\','/') | ConvertTo-Json -Compress
$serverSettings = @(
    'PORT=8787'
    'FIREBASE_PROJECT_ID=utechat-ab9d8'
    "AGORA_APP_ID=$AgoraAppId"
    "AGORA_APP_CERTIFICATE=$plainCertificate"
    "GOOGLE_APPLICATION_CREDENTIALS=$credentialEnv"
)
[IO.File]::WriteAllLines($envPath, $serverSettings, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllLines($clientPath, @(
    "utechatAgoraAppId=$AgoraAppId"
    "utechatAgoraTokenServerUrl=$TokenServerUrl"
), [Text.UTF8Encoding]::new($false))
$plainCertificate = $null
Write-Host 'Da luu cau hinh local. Certificate chi nam trong server/call-token/.env (Git ignore).'
Write-Host 'Chay npm start tai server/call-token, sau do build/cai lai APK len ca hai may.'
