# 05-wallpapers.ps1  (version 2)
# Sends your own photos to the TV launcher as wallpapers, no rebuild needed.
#   Put 1920x1080 .jpg/.png files in a folder called wallpapers (next to this script), then:
#   powershell -ExecutionPolicy Bypass -File .\05-wallpapers.ps1
#   Go back to the built-in nature photos:  add -Clear
param([string]$AdbPath = "", [string]$Folder = ".\wallpapers", [switch]$Clear, [string]$TvIp = "", [int]$Port = 5555)
$ErrorActionPreference = "Continue"
function Fail($m) { Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }
if ($AdbPath -ne "") { $env:Path = (Split-Path $AdbPath) + ";" + $env:Path }
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    $hit = @(Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Directory -Filter "Google.PlatformTools*" -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName "platform-tools\adb.exe" }) | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($hit) { $env:Path = (Split-Path $hit) + ";" + $env:Path }
}
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { Fail "adb not found. Use -AdbPath." }
if ($TvIp -ne "") { $serial = "${TvIp}:${Port}" }
elseif (Test-Path ".\last_tv.txt") { $serial = (Get-Content ".\last_tv.txt" -Raw).Trim() }
else { Fail "No TV known. Pass -TvIp x.x.x.x" }
Write-Host "TV: $serial"
& adb connect $serial | Out-Null
if ((& adb -s $serial get-state 2>$null) -ne "device") { Fail "TV not connected." }
$dest = "/sdcard/Android/data/com.mihir.tvhome/files/wallpapers"
& adb -s $serial shell "mkdir -p $dest" | Out-Null
if ($Clear) {
    & adb -s $serial shell "rm -f $dest/*" | Out-Null
    Write-Host "Cleared. The launcher will use its built-in photos." -ForegroundColor Green
} else {
    $files = Get-ChildItem $Folder -File -ErrorAction SilentlyContinue | Where-Object { $_.Extension -match "^\.(jpg|jpeg|png|webp)$" }
    if (-not $files) { Fail "No images found in $Folder" }
    & adb -s $serial shell "rm -f $dest/*" | Out-Null
    foreach ($f in $files) { Write-Host "  pushing $($f.Name)"; & adb -s $serial push $f.FullName "$dest/$($f.Name)" | Out-Null }
    Write-Host "Pushed $($files.Count) photo(s)." -ForegroundColor Green
}
& adb -s $serial shell "am force-stop com.mihir.tvhome" | Out-Null
& adb -s $serial shell "input keyevent KEYCODE_HOME" | Out-Null
Write-Host "Done. Your TV's home screen restarted; the new photos start from the first one."
