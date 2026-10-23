@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title clean-tv

rem Scripts live in .\windows in the repo, or right next to this file in a flat folder.
if exist "windows\01-backup.ps1" (set "S=windows\") else (set "S=")
set "PS=powershell -NoProfile -ExecutionPolicy Bypass -File"
set "TVIP="
set "IPARG="
set "LAST="
if exist "last_tv.txt" for /f "usebackq delims=" %%a in ("last_tv.txt") do set "LAST=%%a"

:menu
cls
set "SHOW=not set - press T"
if defined LAST set "SHOW=%LAST% [from last backup]"
if defined TVIP set "SHOW=%TVIP%"
echo ==========================================
echo   clean-tv - Android TV tools
echo ==========================================
echo   TV: %SHOW%
echo.
echo   1  Back up the TV (do this first)
echo   2  Debloat - preview only
echo   3  Debloat - apply
echo   4  Restore apps the debloat disabled
echo   5  Restore everything enabled at backup time
echo   6  Install the launcher (needs tvhome.apk in this folder)
echo   7  Go back to the stock home screen
echo   8  Push my wallpapers
echo   9  Use the built-in wallpapers again
echo   R  Reset the launcher (fixes a stuck cursor)
echo   T  Set the TV's IP address
echo   0  Exit
echo.
set "C="
set /p "C=Pick one: "
if "%C%"=="1" goto backup
if "%C%"=="2" goto dry
if "%C%"=="3" goto debloat
if "%C%"=="4" goto restore
if "%C%"=="5" goto restoreall
if "%C%"=="6" goto install
if "%C%"=="7" goto revert
if "%C%"=="8" goto wall
if "%C%"=="9" goto wallclear
if /i "%C%"=="R" goto reset
if /i "%C%"=="T" goto setip
if "%C%"=="0" goto :eof
goto menu

:setip
set "TVIP="
set /p "TVIP=TV IP address, like 10.0.0.28 (Enter to clear): "
if defined TVIP (set "IPARG=-TvIp %TVIP%") else (set "IPARG=")
goto menu

:backup
%PS% "%S%01-backup.ps1" %IPARG%
goto done

:dry
%PS% "%S%02-debloat.ps1" -DryRun %IPARG%
goto done

:debloat
%PS% "%S%02-debloat.ps1" %IPARG%
goto done

:restore
%PS% "%S%03-restore.ps1" %IPARG%
goto done

:restoreall
%PS% "%S%03-restore.ps1" -Everything %IPARG%
goto done

:install
%PS% "%S%04-install-launcher.ps1" %IPARG%
goto done

:revert
%PS% "%S%04-install-launcher.ps1" -Revert %IPARG%
goto done

:wall
%PS% "%S%05-wallpapers.ps1" %IPARG%
goto done

:wallclear
%PS% "%S%05-wallpapers.ps1" -Clear %IPARG%
goto done

:reset
if not exist "%S%06-reset-launcher.ps1" goto noreset
%PS% "%S%06-reset-launcher.ps1" %IPARG%
goto done

:noreset
echo 06-reset-launcher.ps1 is missing. Copy it next to the other scripts.
goto done

:done
echo.
pause
goto menu
