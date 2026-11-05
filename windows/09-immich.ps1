# 09-immich.ps1
# Sets up Immich photos as wallpapers. You type your Immich address and an API key here on the PC
# (easy typing), the script checks them, and then sends them to the TV. The TV keeps them in the
# launcher's private storage. Photos are streamed from your server when they are shown, not saved. Nothing about your server is ever saved in this repo or on this PC.
#   powershell -ExecutionPolicy Bypass -File .\09-immich.ps1
#   Add -TvIp x.x.x.x if the script does not know your TV yet.
#   Add -Insecure if your Immich uses https with your own (self-signed) certificate.
#   Add -Forget to make the TV forget the server and delete the Immich photos it saved.
# Make the API key in Immich: Account Settings > API Keys > New API Key, and tick only
#   asset.read, asset.view, album.read, person.read
# The key can only read. It cannot upload, change or delete anything.
param([string]$AdbPath = "", [string]$TvIp = "", [int]$Port = 5555, [string]$Url = "", [switch]$Insecure, [switch]$Forget)
$ErrorActionPreference = "Continue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 -bor [Net.ServicePointManager]::SecurityProtocol
if ($Insecure) { [Net.ServicePointManager]::ServerCertificateValidationCallback = { $true } }
function Fail($m) { Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }

# ---- find adb and the TV (same way the other scripts do)
if ($AdbPath -ne "") { $env:Path = (Split-Path $AdbPath) + ";" + $env:Path }
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    $hit = @(Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Directory -Filter "Google.PlatformTools*" -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName "platform-tools\adb.exe" }) | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($hit) { $env:Path = (Split-Path $hit) + ";" + $env:Path }
}
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { Fail "adb not found. Use -AdbPath." }
if ($TvIp -ne "") { $serial = "${TvIp}:${Port}" }
elseif (Test-Path ".\last_tv.txt") { $serial = (Get-Content ".\last_tv.txt" -Raw).Trim() }
else { Fail "No TV known. Pass -TvIp x.x.x.x or run the backup once." }
$dest = "/sdcard/Android/data/com.mihir.tvhome/files"

function Send-Config([string]$json) {
    & adb connect $serial | Out-Null
    if ((& adb -s $serial get-state 2>$null) -ne "device") { Fail "TV not connected ($serial)." }
    & adb -s $serial shell "mkdir -p $dest" | Out-Null
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("immich-" + [Guid]::NewGuid().ToString("N") + ".json")
    try {
        [IO.File]::WriteAllText($tmp, $json, (New-Object Text.UTF8Encoding($false)))
        & adb -s $serial push $tmp "$dest/immich.json" | Out-Null
    } finally {
        if (Test-Path $tmp) { Remove-Item $tmp -Force -ErrorAction SilentlyContinue }
    }
    & adb -s $serial shell "input keyevent KEYCODE_HOME" | Out-Null
    # The launcher reads the file, keeps it in private storage and deletes it. Wait for that.
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Seconds 1
        $left = (& adb -s $serial shell "[ -f $dest/immich.json ] && echo yes || echo no") -join ""
        if ($left.Trim() -eq "no") { return $true }
    }
    return $false
}

if ($Forget) {
    Write-Host "TV: $serial"
    $ok = Send-Config '{"forget":true}'
    if ($ok) { Write-Host "Done. The TV forgot the Immich server and removed its saved photos." -ForegroundColor Green }
    else { Write-Host "Sent, but the TV has not picked it up yet. Open the home screen on the TV." -ForegroundColor Yellow }
    exit 0
}

# ---- ask for the address and key
if ($Url -eq "") { $Url = Read-Host "Immich address (like http://192.168.1.20:2283)" }
$Url = $Url.Trim().TrimEnd('/')
if ($Url -eq "") { Fail "No address entered." }
if ($Url -notmatch '^https?://') { $Url = "http://$Url" }
if ($Url -match '/api$') { $Url = $Url.Substring(0, $Url.Length - 4) }
if ($env:IMMICH_API_KEY) { $key = $env:IMMICH_API_KEY }
else {
    $secure = Read-Host "API key (typing is hidden)" -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    $key = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}
$key = $key.Trim()
if ($key -eq "") { Fail "No key entered." }

# One request. Returns @{ Code; Text; Bytes; Type }. Never throws, never shows the key.
function Call([string]$method, [string]$path, $body = $null, [switch]$Binary) {
    $r = @{ Code = 0; Text = ""; Bytes = 0; Type = "" }
    try {
        $req = [System.Net.HttpWebRequest]::Create("$Url/api$path")
        $req.Method = $method; $req.Timeout = 20000; $req.AllowAutoRedirect = $false
        $req.Headers.Add("x-api-key", $key); $req.Accept = "application/json"
        if ($body -ne $null) {
            $json = ConvertTo-Json -InputObject $body -Depth 8 -Compress
            $bytes = [Text.Encoding]::UTF8.GetBytes($json)
            $req.ContentType = "application/json"; $req.ContentLength = $bytes.Length
            $st = $req.GetRequestStream(); $st.Write($bytes, 0, $bytes.Length); $st.Close()
        }
        $resp = $null
        try { $resp = $req.GetResponse() } catch [System.Net.WebException] { $resp = $_.Exception.Response; if ($resp -eq $null) { $r.Text = $_.Exception.Message; return $r } }
        $r.Code = [int]$resp.StatusCode; $r.Type = [string]$resp.ContentType
        $ms = New-Object IO.MemoryStream; $resp.GetResponseStream().CopyTo($ms); $resp.Close()
        $r.Bytes = $ms.Length
        if (-not $Binary) { $r.Text = [Text.Encoding]::UTF8.GetString($ms.ToArray()) }
    } catch { $r.Text = $_.Exception.Message }
    return $r
}
function Parse($t) { try { return ($t | ConvertFrom-Json) } catch { return $null } }
function Hide([string]$t) {
    try { $u = [Uri]$Url; $t = $t.Replace($u.Authority, "<server>").Replace($u.Host, "<server>") } catch { }
    return $t.Replace($key, "***")
}

Write-Host ""
Write-Host "Checking your Immich server..." -ForegroundColor Cyan
$v = Call "GET" "/server/version"
if ($v.Code -ne 200) {
    Write-Host "  Could not reach it (code $($v.Code)) $(Hide $v.Text)" -ForegroundColor Red
    Write-Host "  Check the address and port. For https with your own certificate add -Insecure." -ForegroundColor Yellow
    exit 1
}
$vo = Parse $v.Text
if ($vo -ne $null) { Write-Host "  Server version $($vo.major).$($vo.minor).$($vo.patch)" -ForegroundColor Green }

# Photos: this proves the key works and has asset.read. Both request styles are tried (Immich 3.2 changed it).
$bodies = @(
    @{ type = "IMAGE"; size = 1; visibility = "timeline" },
    @{ size = 1; filter = @{ type = @{ eq = "IMAGE" }; visibility = @{ eq = "timeline" }; trashedAt = @{ eq = $null } } }
)
$firstId = ""; $found = $false; $worst = 0
foreach ($b in $bodies) {
    $s = Call "POST" "/search/metadata" $b
    if ($s.Code -eq 200) {
        $found = $true
        $o = Parse $s.Text
        if ($o -ne $null -and $o.assets -ne $null -and $o.assets.items -ne $null -and @($o.assets.items).Count -gt 0) { $firstId = [string]@($o.assets.items)[0].id }
        break
    }
    if ($s.Code -eq 401 -or $s.Code -eq 403) { $worst = $s.Code; break }
}
if ($worst -eq 401) { Write-Host "  The API key was rejected. Check that you copied all of it." -ForegroundColor Red; exit 1 }
if ($worst -eq 403) { Write-Host "  The key is missing asset.read. Make a new key with the four permissions listed at the top of this script." -ForegroundColor Red; exit 1 }
if ($found) { Write-Host "  Photos: OK" -ForegroundColor Green } else { Write-Host "  Photos: the search was not accepted. The TV will try other formats itself." -ForegroundColor Yellow }

$a = Call "GET" "/albums"
if ($a.Code -eq 200) { Write-Host "  Albums: OK ($(@(Parse $a.Text).Count))" -ForegroundColor Green }
else { Write-Host "  Albums: not allowed (code $($a.Code)). Add album.read to the key to pick albums on the TV." -ForegroundColor Yellow }
$p = Call "GET" "/people?size=1"
if ($p.Code -eq 200) { Write-Host "  People: OK" -ForegroundColor Green }
else { Write-Host "  People: not allowed (code $($p.Code)). Add person.read to the key to pick people on the TV." -ForegroundColor Yellow }
if ($firstId -match '^[0-9A-Za-z-]{8,64}$') {
    $t = Call "GET" "/assets/$firstId/thumbnail?size=preview" -Binary
    if ($t.Code -eq 200) { Write-Host "  Picture download: OK" -ForegroundColor Green }
    else { Write-Host "  Picture download: not allowed (code $($t.Code)). Add asset.view to the key." -ForegroundColor Red; exit 1 }
}

Write-Host ""
Write-Host "TV: $serial"
$ans = Read-Host "Send these to the TV now? (Y/n)"
if ($ans -match '^[nN]') { Write-Host "Cancelled. Nothing was sent."; exit 0 }
$cfg = [ordered]@{ url = $Url; key = $key; insecure = [bool]$Insecure }
$ok = Send-Config (ConvertTo-Json -InputObject $cfg -Compress)
Write-Host ""
if ($ok) {
    Write-Host "Done. The TV has your Immich details and will stream your photos when it shows them." -ForegroundColor Green
    Write-Host "On the TV: Wallpapers > Immich settings to choose albums, people or single photos."
} else {
    Write-Host "Sent, but the TV has not picked it up yet. Open the home screen on the TV and wait about 10 seconds." -ForegroundColor Yellow
    Write-Host "If it never does, make sure the new tvhome.apk is installed (menu option 6)."
}
