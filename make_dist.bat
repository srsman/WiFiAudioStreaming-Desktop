@echo off
setlocal

set DIST_DIR=dist
set BUILD_APP_DIR=build\compose\binaries\main\app\WiFi Audio Streaming

echo [1/4] Creating dist directory...
if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"
if not exist "%DIST_DIR%\app" mkdir "%DIST_DIR%\app"

echo [2/4] Copying built application files...
:: Clear old files first to avoid mess
if exist "%DIST_DIR%\app\WiFi Audio Streaming" rd /s /q "%DIST_DIR%\app\WiFi Audio Streaming"
xcopy /E /I /Y "%BUILD_APP_DIR%" "%DIST_DIR%\app\WiFi Audio Streaming" > nul

echo [3/4] Copying service scripts...
copy /Y "service\wifiaudio-service.xml" "%DIST_DIR%\" > nul
copy /Y "service\install.bat" "%DIST_DIR%\" > nul
copy /Y "service\uninstall.bat" "%DIST_DIR%\" > nul
copy /Y "service\self_restart.bat" "%DIST_DIR%\" > nul

echo [4/4] Checking for WinSW...
if exist "service\wifiaudio-service.exe" (
    copy /Y "service\wifiaudio-service.exe" "%DIST_DIR%\" > nul
    echo [SUCCESS] Package is ready in 'dist' folder!
) else (
    echo [WARNING] wifiaudio-service.exe not found in service folder.
)

pause
