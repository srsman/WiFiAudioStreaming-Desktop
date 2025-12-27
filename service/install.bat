@echo off
cd /d "%~dp0"
:: This script installs the WiFi Audio Streaming service.
:: It requires Administrative privileges.

NET SESSION >nul 2>&1
if %errorLevel% neq 0 (
    echo [ERROR] Please run this script as Administrator.
    pause
    exit /b 1
)

if not exist "wifiaudio-service.exe" (
    echo [ERROR] wifiaudio-service.exe not found! 
    echo Please download WinSW.exe from https://github.com/winsw/winsw/releases 
    echo and rename it to wifiaudio-service.exe in this folder.
    pause
    exit /b 1
)

echo [INFO] Installing service...
wifiaudio-service.exe install

echo [INFO] Configuring Firewall...
netsh advfirewall firewall add rule name="WiFiAudioStreaming-TCP-Global" dir=in action=allow protocol=TCP localport=8080 profile=any enable=yes
netsh advfirewall firewall add rule name="WiFiAudioStreaming-UDP-Global" dir=in action=allow protocol=UDP localport=9090,9091,9092 profile=any enable=yes

echo [INFO] Starting service...
wifiaudio-service.exe start

echo [SUCCESS] Service installed and started.
pause
