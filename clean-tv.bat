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
set "TVNAME="
if exist "last_tv.txt" for /f "usebackq delims=" %%a in ("last_tv.txt") do set "LAST=%%a"
rem Pick the TV you used last time (saved in tv-devices.json by option T).
if exist "%S%08-tv-devices.ps1" for /f "usebackq tokens=1,* delims=|" %%a in (`%PS% "%S%08-tv-devices.ps1" -Last`) do (set "TVIP=%%a" & set "TVNAME=%%b")
if defined TVIP set "IPARG=-TvIp %TVIP%"

:menu
cls
set "SHOW=not set - press T"
if defined LAST set "SHOW=%LAST% [from last backup]"
if defined TVIP set "SHOW=%TVIP%"
if defined TVIP if defined TVNAME set "SHOW=%TVNAME% [%TVIP%]"
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
echo   S  Turn off Google TV's own screensaver
echo   E  Turn Google TV's screensaver back on
echo   T  Choose or add a TV
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
if /i "%C%"=="S" goto ssoff
if /i "%C%"=="E" goto sson
if /i "%C%"=="T" goto setip
if "%C%"=="0" goto :eof
goto menu

:setip
if not exist "%S%08-tv-devices.ps1" goto setipold
cls
echo ==========================================
echo   Choose a TV
echo ==========================================
set "COUNT=0"
for /f "usebackq delims=" %%L in (`%PS% "%S%08-tv-devices.ps1" -List`) do (
    echo   %%L
    set /a COUNT+=1
)
if "%COUNT%"=="0" goto addtv
echo.
echo   N  Add a new TV
echo   X  Forget a TV
echo   C  Clear my choice
echo   0  Back
echo.
set "PICK="
set /p "PICK=Pick a number or letter: "
if not defined PICK goto menu
if /i "%PICK%"=="N" goto addtv
if /i "%PICK%"=="X" goto forgettv
if /i "%PICK%"=="C" goto cleartv
if "%PICK%"=="0" goto menu
set "GOTIP="
set "GOTNAME="
for /f "usebackq tokens=1,* delims=|" %%a in (`%PS% "%S%08-tv-devices.ps1" -Get`) do (set "GOTIP=%%a" & set "GOTNAME=%%b")
if not defined GOTIP goto badpick
set "TVIP=%GOTIP%"
set "TVNAME=%GOTNAME%"
set "IPARG=-TvIp %TVIP%"
goto menu

:addtv
echo.
set "NEWIP="
set /p "NEWIP=TV IP address, like 192.168.1.50 (Enter to cancel): "
if not defined NEWIP goto menu
set "NEWNAME="
set /p "NEWNAME=Name for this TV, like Living room (Enter to skip): "
set "GOTIP="
set "GOTNAME="
for /f "usebackq tokens=1,* delims=|" %%a in (`%PS% "%S%08-tv-devices.ps1" -Add`) do (set "GOTIP=%%a" & set "GOTNAME=%%b")
if not defined GOTIP goto badip
set "TVIP=%GOTIP%"
set "TVNAME=%GOTNAME%"
set "IPARG=-TvIp %TVIP%"
goto menu

:forgettv
echo.
set "PICK="
set /p "PICK=Number of the TV to forget (Enter to cancel): "
if not defined PICK goto setip
set "GOTIP="
set "GOTNAME="
for /f "usebackq tokens=1,* delims=|" %%a in (`%PS% "%S%08-tv-devices.ps1" -Remove`) do (set "GOTIP=%%a" & set "GOTNAME=%%b")
if not defined GOTIP goto badpick
echo   Forgot %GOTNAME%.
if "%GOTIP%"=="%TVIP%" (
    set "TVIP="
    set "TVNAME="
    set "IPARG="
)
pause
goto setip

:cleartv
%PS% "%S%08-tv-devices.ps1" -ClearLast
set "TVIP="
set "TVNAME="
set "IPARG="
goto menu

:badpick
echo.
echo   That is not one of the numbers in the list.
pause
goto setip

:badip
echo.
echo   That does not look like an IP address. It should look like 192.168.1.50
pause
goto setip

:setipold
rem Used when 08-tv-devices.ps1 is not next to the other scripts.
set "TVIP="
set "TVNAME="
set /p "TVIP=TV IP address, like 192.168.1.50 (Enter to clear): "
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

:ssoff
if not exist "%S%07-screensaver.ps1" goto nosaver
%PS% "%S%07-screensaver.ps1" %IPARG%
goto done

:sson
if not exist "%S%07-screensaver.ps1" goto nosaver
%PS% "%S%07-screensaver.ps1" -On %IPARG%
goto done

:nosaver
echo 07-screensaver.ps1 is missing. Copy it next to the other scripts.
goto done

:noreset
echo 06-reset-launcher.ps1 is missing. Copy it next to the other scripts.
goto done

:done
echo.
pause
goto menu
