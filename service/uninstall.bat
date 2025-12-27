@echo off
cd /d "%~dp0"
:: This script uninstalls the WiFi Audio Streaming service.
:: It requires Administrative privileges.

NET SESSION >nul 2>&1
if %errorLevel% neq 0 (
    echo [ERROR] Please run this script as Administrator.
    pause
    exit /b 1
)

echo [INFO] Stopping service...
wifiaudio-service.exe stop

echo [INFO] Forcesly killing any remaining processes...
taskkill /F /IM "WiFi Audio Streaming.exe" /T >nul 2>&1

echo [INFO] Uninstalling service...
wifiaudio-service.exe uninstall

echo [INFO] Cleaning up firewall rules...
netsh advfirewall firewall delete rule name="WiFiAudioStreaming-TCP-Global"
netsh advfirewall firewall delete rule name="WiFiAudioStreaming-UDP-Global"
netsh advfirewall firewall delete rule name="WiFiAudioStreaming-TCP"
netsh advfirewall firewall delete rule name="WiFiAudioStreaming-UDP"

echo [SUCCESS] Service uninstalled.
pause
