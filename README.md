# clean-tv (Windows edition)

Clean up a slow, ad-filled Android TV / Google TV box from a **Windows PC**, then give it a fast,
minimal **Apple TV-style home screen**: a dock of your favourite apps over rotating nature wallpapers.

Everything runs over Wi-Fi with `adb`. Packages are **disabled, never uninstalled**, so every change
can be undone with a script.

> Based on [mihirgupta0900/tv-home](https://github.com/mihirgupta0900/tv-home) (MIT), which targets
> macOS and Claude Code. This edition adds PowerShell scripts for Windows, a hold-to-edit app menu,
> and custom wallpapers without rebuilding.

## What you get

- **Backup first.** Package lists, settings, current launcher and a screenshot are saved before any change.
- **Debloat with prompts.** Disables ad-tech, feedback services and streaming apps you don't use, one
  group at a time, with a screenshot check after each. A protected list covers voice, remote, Bluetooth and Play services.
- **A tiny launcher.** Dock, app grid, clock, crossfading wallpapers. No ads, no recommendations, no network use for content.
- **Hold OK on an app** to Move, Remove from dock, Hide, Delete (apps you installed) or Reset layout.
- **Your own wallpapers** pushed from a folder, no rebuild needed.
- **Undo scripts** for the debloat and for the home screen.

## Tested on

One device: **onn 4K box (Google TV, Android 14)**. The debloat lists are built for that box. On any other
TV, run `-DryRun` first and edit the `$Groups` list at the top of `02-debloat.ps1`.

## Requirements

- Windows 10/11 and a TV on the same Wi-Fi as your PC
- [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) (`winget install Google.PlatformTools`)

## Quick start

1. **TV:** Settings > System > About, click **Build** 7 times. Then Developer options: turn on **USB debugging**
   and **Network debugging** (or Wireless debugging). Note the TV's IP in Settings > Network.
2. **Get the launcher APK.** Download `tvhome.apk` from this repo's **Releases** page.
3. Clone or download this repo. Put `tvhome.apk` in the repo's root folder and open Command Prompt there.
4. Back up the TV (read-only):
   ```
   powershell -ExecutionPolicy Bypass -File .\windows\01-backup.ps1 -TvIp 192.168.1.50
   ```
   Accept **Allow USB debugging?** on the TV (Always allow) and run it again if it stopped.
5. Preview the debloat, then run it:
   ```
   powershell -ExecutionPolicy Bypass -File .\windows\02-debloat.ps1 -DryRun
   powershell -ExecutionPolicy Bypass -File .\windows\02-debloat.ps1
   ```
   Read each yes/no carefully. The default is No.
6. Install the home screen:
   ```
   powershell -ExecutionPolicy Bypass -File .\windows\04-install-launcher.ps1
   ```
   Then open an app and press **Home** on the remote to check it returns to the new home screen.

## Commands

Run from the repo root. Prefix each with `powershell -ExecutionPolicy Bypass -File`.

| Script | Does |
|---|---|
| `.\windows\01-backup.ps1 [-TvIp x.x.x.x]` | Backup and inventory. Without `-TvIp` it scans your network. |
| `.\windows\02-debloat.ps1 [-DryRun]` | Disables packages group by group, with prompts. |
| `.\windows\03-restore.ps1` | Re-enables everything the debloat disabled. |
| `.\windows\03-restore.ps1 -Only pkg1,pkg2` | Re-enables only the packages you name. |
| `.\windows\03-restore.ps1 -Everything` | Re-enables everything that was enabled at backup time. |
| `.\windows\04-install-launcher.ps1` | Installs the launcher and makes it your home screen. |
| `.\windows\04-install-launcher.ps1 -Revert` | Goes back to the stock home screen. |
| `.\windows\05-wallpapers.ps1` | Pushes your `wallpapers` folder (1920x1080 .jpg/.png) to the TV. |
| `.\windows\05-wallpapers.ps1 -Clear` | Goes back to the built-in photos. |

If `adb` isn't found, add `-AdbPath "C:\path\to\adb.exe"`.

## Customise

- **On the TV:** hold OK on a tile to rearrange. The layout is saved on the TV.
- **Wallpapers:** see `05-wallpapers.ps1`. Photos you push stay on your TV and never go in this repo.

## Troubleshooting

- **`adb` not found:** open a new terminal after installing, or use `-AdbPath`.
- **`failed to authenticate`:** accept the **Allow USB debugging?** prompt on the TV.
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`:** the installed launcher was signed with a different key.
  `04-install-launcher.ps1` offers to switch back to the stock home, remove it and reinstall. 
- **Home button misbehaves:** run `04-install-launcher.ps1 -Revert`. On Google TV the setup app
  (`com.google.android.tungsten.setupwraith`) can hijack Home, which is why the script disables it with the stock launcher.
- **Wi-Fi forgot its password after the swap:** it happened once on the tested box. Re-enter it.

## Safety

- Use at your own risk. This is not affiliated with Google, Walmart/onn or any TV maker.
- Your `backups/` folder contains device identifiers and copies of installed APKs. It is git-ignored; never publish it.
- The scripts only run `pm disable-user`, `pm enable`, `adb install`, and `adb uninstall` of the launcher itself.
  The Delete option in the launcher uses Android's own uninstall dialog.

## Credits and licence

- Launcher and original tooling: [Mihir Gupta](https://github.com/mihirgupta0900/tv-home), MIT.
- Windows scripts and launcher changes: this repo's contributors, MIT. See [LICENSE](LICENSE).
- Wallpapers are from Wikimedia Commons under CC BY-SA. Attribution is in
  [`launcher/WALLPAPER-CREDITS.md`](launcher/WALLPAPER-CREDITS.md). 
