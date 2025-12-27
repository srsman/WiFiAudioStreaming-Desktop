@echo off
setlocal
cd /d "%~dp0"

echo [MAINTENANCE] Received remote restart/update command.
echo [MAINTENANCE] Waiting 3 seconds for web response to finish...
timeout /t 3 /nobreak > nul

echo [MAINTENANCE] Stopping WiFiAudioStreamingService...
wifiaudio-service.exe stop > nul 2>&1

echo [MAINTENANCE] Force killing any orphan app processes...
taskkill /F /IM "WiFi Audio Streaming.exe" /T > nul 2>&1

:: Check if there's a pending update in 'update_temp'
if exist "update_temp\" (
    echo [MAINTENANCE] Found pending update. Applying files...
    xcopy /E /Y /H /R "update_temp\*" ".\"
    rd /S /Q "update_temp"
    echo [MAINTENANCE] Update applied.
)

echo [MAINTENANCE] Starting service back up...
wifiaudio-service.exe start

echo [MAINTENANCE] Service has been cycled.
exit
