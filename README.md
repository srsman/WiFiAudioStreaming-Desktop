# WiFi Audio Streaming (Desktop) - Pro Edition

[![GitHub](https://img.shields.io/badge/GitHub-v1.0.0--Pro-blue?logo=github)](https://github.com/srsman/WiFiAudioStreaming-Desktop)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

> **Wireless High-Fidelity Audio Server with Web Receiver & Remote Management.**

---

[English](#english) | [简体中文](#简体中文)

---

<a name="english"></a>

## 🌐 Overview

WiFi Audio Streaming Pro turns your computer into a powerful **wireless audio broadcast station**. Unlike simple transmitters, this version features a a **Web Receiver** and a **Windows Service Mode**, allowing any device with a browser to listen to your PC audio without installing any apps.

### ✨ Key Features

-   **🚀 Web Receiver (No App Required)**: Open a URL (e.g., `http://192.168.1.5:8080`) on any smartphone, tablet, or PC to start listening immediately.
-   **⚙️ Windows Service Mode**: Run as a silent background service (Headless). No window needed, starts automatically with Windows.
-   **🎛️ Remote Management Console**: Switch audio sources, restart service, or view logs directly from your browser.
-   **📥 Web Recording**: Record live streams directly in the browser and download them as `.webm` files.
-   **🎤 Audio Optimization**: Built-in **2.0x Digital Gain** and **Mono-Mode** for microphones to ensure crystal-clear and loud communication.
-   **🔄 Remote OTA Updates**: Upload a ZIP package via the web interface to update the server remotely—no physical access required.
-   **🛡️ Auto Firewall Setup**: Includes automated scripts to configure Windows Firewall rules for seamless network access.

---

## 🚀 Getting Started

### 1. Portable Distribution
The "Pro" build is available in the `dist/` directory. It is completely portable and includes its own Java environment.

### 2. Run as a Windows Service (Recommended)
1.  Enter the `dist/` folder.
2.  **Right-click `install.bat` and Run as Administrator**. 
    - This will install the service, configure the firewall, and start the broadcast.
3.  Use **`uninstall.bat`** (Run as Admin) to completely remove the service and firewall rules.

### 3. How to Listen
-   **From Android/iOS/PC**: Open your browser and go to `http://YOUR_PC_IP:8080`.
-   **From WiFiAudio App**: Use standard port `9090` (Multicast).

---

## 🛠️ Remote Maintenance Console

Access the hidden maintenance tools by clicking **"Maintenance Tools"** at the bottom of the web receiver page:
-   **Switch Source**: Change between Microphone, Stereo Mix, or Virtual Cable on the fly without stopping the server.
-   **OTA Update**: Upload a ZIP of the `dist` folder to update the entire server remotely.
-   **Live Logs**: View real-time output to debug connection issues.

---

<a name="简体中文"></a>

## 🌐 项目概述

WiFi Audio Streaming Pro 将您的电脑转变为一个强大的**无线音频广播站**。与普通传输器不同，专业版增加了 **Web 接收端** 和 **Windows 服务模式**，允许任何带浏览器的设备直接听取您的 PC 音频，无需安装任何客户端 App。

### ✨ 核心特性

-   **🚀 Web 接收端 (无需 App)**：在任何手机、平板或电脑浏览器打开 URL（如 `http://192.168.1.5:8080`）即可立即收听。
-   **⚙️ Windows 服务模式**：支持作为静默后台服务运行（无窗口）。支持开机自启，由 WinSW 守护，崩溃自动重启。
-   **🎛️ 远程管理控制台**：直接通过浏览器切换音源（如从麦克风切到系统声音）、重启服务或查看运行日志。
-   **📥 网页端录音**：在浏览器中一键录制直播音频并自动下载为 `.webm` 文件。
-   **🎤 麦克风深度优化**：内置 **2.0倍数字增益** 和 **单声道自动优化**，彻底解决麦克风声音小、有杂音的问题。
-   **🔄 远程 OTA 升级**：通过网页上传 ZIP 压缩包即可完成远程更新，无需物理接触服务器电脑。
-   **🛡️ 自动防火墙配置**：包含自动化脚本，一键配置 Windows 防火墙规则，确保全网络畅通。

---

## 🚀 快速开始

### 1. 便携部署包
“专业版”程序位于 `dist/` 目录中。它是全绿色的，包含了运行所需的 Java 环境。

### 2. 作为 Windows 服务运行 (推荐)
1.  进入 `dist/` 文件夹。
2.  **右键点击 `install.bat` 并选择“以管理员身份运行”**。
    - 脚本会自动安装服务、配置防火墙并启动广播。
3.  如需卸载，请运行 **`uninstall.bat`** (管理员)。

### 3. 如何收听
-   **手机/平板/电脑浏览器**：访问 `http://你的电脑IP:8080`。
-   **WiFiAudio 原生 App**：使用默认端口 `9090` (组播模式)。

---

## 🛠️ 远程维护控制台

点击 Web 接收页面底部的 **"Maintenance Tools"** 即可进入隐藏的管理界面：
-   **切换音源**：在线实时切换麦克风、立体声混音或虚拟声卡，无需重启服务。
-   **OTA 更新**：上传 `dist` 目录的新版 ZIP 包，系统会自动完成覆盖并重启。
-   **实时日志**：远程查看服务器最近 100 行输出，方便排查连接问题。

---

## 💻 技术栈

-   **Backend**: Kotlin, Ktor Server (WebSockets)
-   **UI**: Jetpack Compose for Desktop
-   **Service**: WinSW (Windows Service Wrapper)
-   **Graphic**: Skiko (Skia for Kotlin)
-   **Audio**: Java Sound API, MediaRecorder API (Web)

## 📄 开源协议
本项目基于 **MIT License** 发布。更多细节请查看 `LICENSE.md` 文件。
