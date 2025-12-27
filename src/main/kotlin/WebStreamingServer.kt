import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.content.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

class WebStreamingServer(
    private val port: Int = 8080,
    private val core: StreamingCore,
    private val onSourceChange: (Int) -> Unit = {}
) {
    private var server: NettyApplicationEngine? = null
    private val sessions = ConcurrentHashMap.newKeySet<DefaultWebSocketServerSession>()

    fun start(audioSettings: AudioSettings_V1) {
        server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            install(WebSockets)
            routing {
                get("/") {
                    // Always reload current settings from the server state
                    call.respondText(getHtmlPage(), io.ktor.http.ContentType.Text.Html)
                }

                get("/system/devices") {
                    val allMixers = javax.sound.sampled.AudioSystem.getMixerInfo()
                    val validDevices = allMixers.mapIndexed { index, info ->
                        val mixer = try { javax.sound.sampled.AudioSystem.getMixer(info) } catch(e: Exception) { null }
                        val isInput = mixer?.isLineSupported(javax.sound.sampled.Line.Info(javax.sound.sampled.TargetDataLine::class.java)) ?: false
                        // Only return devices that can actually record audio
                        if (isInput && !info.name.startsWith("Port", ignoreCase = true)) {
                            mapOf("index" to index, "name" to info.name)
                        } else null
                    }.filterNotNull()
                    
                    val json = validDevices.joinToString(",", "[", "]") { 
                        "{\"index\": ${it["index"]}, \"name\": \"${it["name"]}\"}"
                    }
                    call.respondText(json, io.ktor.http.ContentType.Application.Json)
                }

                post("/system/change-source") {
                    val index = call.request.queryParameters["index"]?.toIntOrNull()
                    if (index != null) {
                        println("[REMOTE] Requesting source change to index: $index")
                        onSourceChange(index)
                        call.respondText("SUCCESS")
                    } else {
                        call.respondText("INVALID INDEX", status = io.ktor.http.HttpStatusCode.BadRequest)
                    }
                }

                post("/system/upload-update") {
                    val multipart = call.receiveMultipart()
                    var fileCreated = false
                    
                    multipart.forEachPart { part ->
                        if (part is PartData.FileItem) {
                            val fileBytes = part.streamProvider().readBytes()
                            val zipFile = File("update.zip")
                            zipFile.writeBytes(fileBytes)
                            
                            // Unzip to update_temp
                            val updateDir = File("update_temp")
                            if (updateDir.exists()) updateDir.deleteRecursively()
                            updateDir.mkdirs()
                            
                            ZipInputStream(zipFile.inputStream()).use { zis ->
                                var entry = zis.nextEntry
                                while (entry != null) {
                                    val newFile = File(updateDir, entry.name)
                                    if (entry.isDirectory) {
                                        newFile.mkdirs()
                                    } else {
                                        newFile.parentFile.mkdirs()
                                        FileOutputStream(newFile).use { fos ->
                                            zis.copyTo(fos)
                                        }
                                    }
                                    entry = zis.nextEntry
                                }
                            }
                            zipFile.delete()
                            fileCreated = true
                        }
                        part.dispose()
                    }
                    
                    if (fileCreated) {
                        println("[REMOTE] Update uploaded and extracted. Restarting...")
                        try {
                            val processBuilder = ProcessBuilder("cmd", "/c", "start", "self_restart.bat")
                            processBuilder.directory(File(System.getProperty("user.dir")))
                            processBuilder.start()
                            
                            CoroutineScope(Dispatchers.Default).launch {
                                delay(1000)
                                System.exit(0)
                            }
                            call.respondText("SUCCESS")
                        } catch (e: Exception) {
                            call.respondText("RESTART FAILED: ${e.message}", status = io.ktor.http.HttpStatusCode.InternalServerError)
                        }
                    } else {
                        call.respondText("NO FILE UPLOADED", status = io.ktor.http.HttpStatusCode.BadRequest)
                    }
                }

                // API for Remote Management
                post("/system/restart") {
                    println("[REMOTE] Restart command received.")
                    try {
                        val processBuilder = ProcessBuilder("cmd", "/c", "start", "self_restart.bat")
                        processBuilder.directory(java.io.File(System.getProperty("user.dir")))
                        processBuilder.start()
                        
                        CoroutineScope(Dispatchers.Default).launch {
                            delay(1000)
                            System.exit(0)
                        }
                        call.respondText("SUCCESS")
                    } catch (e: Exception) {
                        call.respondText("ERROR: ${e.message}", status = io.ktor.http.HttpStatusCode.InternalServerError)
                    }
                }

                get("/system/logs") {
                    val logFile = java.io.File("wifiaudio-service.out.log")
                    if (logFile.exists()) {
                        val lines = logFile.readLines().takeLast(100).joinToString("\n")
                        call.respondText(lines)
                    } else {
                        call.respondText("No logs found.")
                    }
                }

                webSocket("/stream") {
                    sessions.add(this)
                    try {
                        for (frame in incoming) {
                            // Keep connection alive
                        }
                    } finally {
                        sessions.remove(this)
                    }
                }
            }
        }.start(wait = false)
        println("[WEB] Server started on http://0.0.0.0:$port")
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }

    suspend fun broadcastAudio(data: ByteArray) {
        if (sessions.isEmpty()) return
        val frame = Frame.Binary(true, data)
        sessions.forEach { session ->
            try {
                session.send(frame)
            } catch (e: Exception) {
                // Session likely closed
            }
        }
    }

    private fun getHtmlPage(): String {
        val settings = core.currentAudioSettings ?: AudioSettings_V1(48000f, 16, 2, 6400)
        return """
<!DOCTYPE html>
<html>
<head>
    <title>WiFi Audio Web Receiver</title>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
        body { font-family: 'Outfit', sans-serif; background: linear-gradient(135deg, #0f0c29, #302b63, #24243e); color: #fff; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; margin: 0; overflow: hidden; }
        .card { background: rgba(255, 255, 255, 0.05); backdrop-filter: blur(20px); border: 1px solid rgba(255, 255, 255, 0.1); padding: 3rem; border-radius: 2rem; box-shadow: 0 25px 50px rgba(0,0,0,0.3); text-align: center; max-width: 450px; width: 90%; transition: transform 0.3s ease; }
        h1 { font-weight: 700; font-size: 2.5rem; margin-bottom: 0.5rem; background: linear-gradient(to right, #00dbde, #fc00ff); -webkit-background-clip: text; -webkit-text-fill-color: transparent; }
        .subtitle { color: rgba(255, 255, 255, 0.6); margin-bottom: 2rem; font-size: 0.9rem; letter-spacing: 1px; }
        .controls { display: flex; flex-direction: column; gap: 1rem; }
        .btn { padding: 1.2rem 2rem; border-radius: 3rem; font-weight: 600; cursor: pointer; transition: all 0.3s cubic-bezier(0.175, 0.885, 0.32, 1.275); font-size: 1rem; border: none; text-transform: uppercase; letter-spacing: 1px; }
        .btn-listen { background: linear-gradient(to right, #00dbde, #fc00ff); color: white; box-shadow: 0 10px 20px rgba(0,219,222,0.3); }
        .btn-listen:hover { transform: translateY(-5px); box-shadow: 0 15px 30px rgba(0,219,222,0.4); }
        .btn-record { background: rgba(255, 255, 255, 0.1); color: #fff; border: 1px solid rgba(255, 255, 255, 0.2); display: none; }
        .btn-record.active { background: #ff4b2b; border-color: #ff4b2b; animation: pulse 1.5s infinite; }
        .btn-maint { margin-top: 1rem; background: transparent; color: rgba(255,255,255,0.3); font-size: 0.7rem; border: 1px solid rgba(255,255,255,0.1); padding: 0.5rem 1rem; }
        .btn-restart { background: #ff4b2b; color: white; display: none; margin-top: 1rem; }
        
        .btn-stop { background: rgba(255, 255, 255, 0.1); color: #fff; border: 1px solid rgba(255, 255, 255, 0.2); display: none; }
        .btn-update { background: #4CAF50; color: white; display: block; margin-top: 1rem; width: 100%; border-radius: 1rem; padding: 0.8rem; }
        
        .status { margin-top: 2rem; font-size: 0.9rem; padding: 0.5rem 1rem; border-radius: 1rem; background: rgba(0,0,0,0.2); display: inline-block; color: rgba(255, 255, 255, 0.4); }
        .playing { color: #00dbde; text-shadow: 0 0 10px rgba(0,219,222,0.5); }
        #timer { font-family: monospace; font-size: 1.2rem; margin-top: 1rem; display: none; color: #ff4b2b; }
        #logView { display: none; background: rgba(0,0,0,0.4); text-align: left; padding: 1rem; border-radius: 10px; font-size: 0.7rem; font-family: monospace; max-height: 150px; overflow-y: auto; white-space: pre-wrap; margin-top: 1rem; color: #aaa; border: 1px solid rgba(255,255,255,0.05); }
        @keyframes pulse { 0% { opacity: 1; } 50% { opacity: 0.7; } 100% { opacity: 1; } }
    </style>
    <link href="https://fonts.googleapis.com/css2?family=Outfit:wght@400;600;700&display=swap" rel="stylesheet">
</head>
<body>
    <div class="card">
        <h1>WiFi Audio</h1>
        <div class="subtitle">PREMIUM WEB RECEIVER</div>
        
        <div class="controls">
            <button id="playBtn" class="btn btn-listen">Start Listening</button>
            <button id="stopBtn" class="btn btn-stop">Stop Listening</button>
            <button id="recordBtn" class="btn btn-record">Start Recording</button>
        </div>
        
        <div id="timer">00:00:00</div>
        <div id="status" class="status">Waiting for interaction...</div>

        <div id="logView"></div>
        
        <div id="maintTools" style="display: none;">
            <div style="margin-top: 1rem; text-align: left; font-size: 0.8rem; color: #888;">Host Audio Source:</div>
            <select id="deviceSelect" class="btn btn-maint" style="width: 100%; margin-top: 0.5rem; text-align: left;"></select>
            <button id="applySourceBtn" class="btn btn-listen" style="padding: 0.6rem; font-size: 0.8rem; margin-top: 0.5rem; width: 100%;">Apply Source</button>
            
            <div style="margin-top: 1.5rem; border-top: 1px solid rgba(255,255,255,0.1); padding-top: 1rem;">
                <div style="text-align: left; font-size: 0.8rem; color: #888;">Remote Update (ZIP):</div>
                <input type="file" id="updateFile" style="margin-top: 0.5rem; font-size: 0.7rem; color: #aaa;">
                <button id="uploadBtn" class="btn btn-update">Upload & Apply Update</button>
            </div>

            <button id="restartBtn" class="btn btn-restart" style="width: 100%;">Restart Service</button>
        </div>
        
        <button id="maintBtn" class="btn btn-maint">Maintenance Tools</button>
    </div>

    <script>
        const playBtn = document.getElementById('playBtn');
        const recordBtn = document.getElementById('recordBtn');
        const stopBtn = document.getElementById('stopBtn');
        const restartBtn = document.getElementById('restartBtn');
        const maintBtn = document.getElementById('maintBtn');
        const maintTools = document.getElementById('maintTools');
        const deviceSelect = document.getElementById('deviceSelect');
        const applySourceBtn = document.getElementById('applySourceBtn');
        const updateFile = document.getElementById('updateFile');
        const uploadBtn = document.getElementById('uploadBtn');
        const statusDiv = document.getElementById('status');
        const timerDiv = document.getElementById('timer');
        const logView = document.getElementById('logView');
        
        maintBtn.onclick = async () => {
            const visible = maintTools.style.display === 'block';
            maintTools.style.display = visible ? 'none' : 'block';
            logView.style.display = visible ? 'none' : 'block';
            if (!visible) {
                // Fetch Logs
                const res = await fetch('/system/logs');
                logView.innerText = await res.text();
                
                // Fetch Devices
                const devRes = await fetch('/system/devices');
                const devices = await devRes.json();
                deviceSelect.innerHTML = '';
                devices.forEach(dev => {
                    const opt = document.createElement('option');
                    opt.value = dev.index;
                    opt.text = dev.name;
                    deviceSelect.appendChild(opt);
                });
            }
        };

        uploadBtn.onclick = async () => {
            if (!updateFile.files[0]) return alert("Please select a ZIP file first.");
            if (!confirm("Confirm upload and apply update? The service will restart.")) return;

            statusDiv.innerText = "Uploading update package...";
            const formData = new FormData();
            formData.append("update", updateFile.files[0]);

            try {
                const res = await fetch('/system/upload-update', {
                    method: 'POST',
                    body: formData
                });

                if (res.ok) {
                    statusDiv.innerText = "Upload successful! Restarting system...";
                    setTimeout(() => location.reload(), 10000);
                } else {
                    statusDiv.innerText = "Update failed: " + (await res.text());
                }
            } catch (e) {
                statusDiv.innerText = "Restarting... Please wait.";
                setTimeout(() => location.reload(), 10000);
            }
        };

        applySourceBtn.onclick = async () => {
            const index = deviceSelect.value;
            statusDiv.innerText = "Switching host audio source...";
            try {
                await fetch('/system/change-source?index=' + index, { method: 'POST' });
                // Small delay to let the server change
                setTimeout(() => {
                    location.reload();
                }, 1500);
            } catch (e) {
                // If the fetch fails because the server is momentarily restarting or busy
                setTimeout(() => location.reload(), 2000);
            }
        };

        restartBtn.onclick = async () => {
            if (confirm("Are you sure you want to restart the background service? This will apply any pending file changes.")) {
                statusDiv.innerText = "Initiating remote restart...";
                await fetch('/system/restart', { method: 'POST' });
                setTimeout(() => {
                    location.reload();
                }, 8000);
            }
        };
        let audioCtx = null;
        let ws = null;
        let nextStartTime = 0;
        let mediaRecorder = null;
        let recordedChunks = [];
        let startTime = 0;
        let timerInterval = null;

        stopBtn.onclick = () => {
            if (ws) ws.close();
            if (audioCtx) {
                audioCtx.close();
                audioCtx = null;
            }
            statusDiv.innerText = "Stopped";
            statusDiv.classList.remove('playing');
            playBtn.style.display = 'block';
            stopBtn.style.display = 'none';
            recordBtn.style.display = 'none';
        };

        playBtn.onclick = () => {
            if (audioCtx) return;
            
            audioCtx = new (window.AudioContext || window.webkitAudioContext)({
                sampleRate: ${settings.sampleRate.toInt()}
            });
            
            // Create a destination for recording
            const dest = audioCtx.createMediaStreamDestination();
            const gainNode = audioCtx.createGain();
            gainNode.connect(audioCtx.destination);
            gainNode.connect(dest);

            statusDiv.innerText = "Connecting...";
            statusDiv.classList.add('playing');
            
            const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            ws = new WebSocket(protocol + '//' + window.location.host + '/stream');
            ws.binaryType = 'arraybuffer';

            ws.onopen = () => {
                statusDiv.innerText = "Live Streaming";
                playBtn.style.display = 'none';
                stopBtn.style.display = 'block';
                recordBtn.style.display = 'block';
                
                try {
                    if (typeof MediaRecorder === 'undefined') {
                        statusDiv.innerText = "Live (No Recording Support)";
                        return;
                    }
                    mediaRecorder = new MediaRecorder(dest.stream);
                    mediaRecorder.ondataavailable = (e) => {
                        if (e.data.size > 0) recordedChunks.push(e.data);
                    };
                    mediaRecorder.onstop = () => {
                        const blob = new Blob(recordedChunks, { type: 'audio/webm' });
                        const url = URL.createObjectURL(blob);
                        const a = document.createElement('a');
                        a.href = url;
                        a.download = 'recorded-audio-' + new Date().getTime() + '.webm';
                        a.click();
                        recordedChunks = [];
                    };
                } catch (e) {
                    console.error("MediaRecorder setup failed:", e);
                }
            };

            ws.onmessage = async (event) => {
                const arrayBuffer = event.data;
                const int16Array = new Int16Array(arrayBuffer);
                const float32Array = new Float32Array(int16Array.length);
                
                for (let i = 0; i < int16Array.length; i++) {
                    float32Array[i] = int16Array[i] / 32768.0;
                }

                const channels = ${settings.channels};
                const frameCount = float32Array.length / channels;
                const audioBuffer = audioCtx.createBuffer(channels, frameCount, ${settings.sampleRate.toInt()});

                for (let c = 0; c < channels; c++) {
                    const channelData = audioBuffer.getChannelData(c);
                    for (let i = 0; i < frameCount; i++) {
                        channelData[i] = float32Array[i * channels + c];
                    }
                }

                const source = audioCtx.createBufferSource();
                source.buffer = audioBuffer;
                // Connect to our gain node (which splits to speakers and recorder)
                source.connect(gainNode);
                
                if (nextStartTime === 0 || nextStartTime < audioCtx.currentTime) {
                    nextStartTime = audioCtx.currentTime + 0.1;
                }
                
                source.start(nextStartTime);
                nextStartTime += audioBuffer.duration;
            };

            ws.onclose = () => {
                stopRecording();
                statusDiv.innerText = "Server Disconnected";
                statusDiv.classList.remove('playing');
                playBtn.style.display = 'block';
                recordBtn.style.display = 'none';
                audioCtx = null;
            };
        };

        recordBtn.onclick = () => {
            if (mediaRecorder.state === 'inactive') {
                startRecording();
            } else {
                stopRecording();
            }
        };

        function startRecording() {
            recordedChunks = [];
            mediaRecorder.start();
            recordBtn.innerText = 'Stop Recording';
            recordBtn.classList.add('active');
            timerDiv.style.display = 'block';
            startTime = Date.now();
            updateTimer();
            timerInterval = setInterval(updateTimer, 1000);
        }

        function stopRecording() {
            if (mediaRecorder && mediaRecorder.state !== 'inactive') {
                mediaRecorder.stop();
                recordBtn.innerText = 'Start Recording';
                recordBtn.classList.remove('active');
                timerDiv.style.display = 'none';
                clearInterval(timerInterval);
            }
        }

        function updateTimer() {
            const elapsed = Math.floor((Date.now() - startTime) / 1000);
            const hrs = Math.floor(elapsed / 3600).toString().padStart(2, '0');
            const mins = Math.floor((elapsed % 3600) / 60).toString().padStart(2, '0');
            const secs = (elapsed % 60).toString().padStart(2, '0');
            timerDiv.innerText = hrs + ":" + mins + ":" + secs;
        }
    </script>
</body>
</html>
        """.trimIndent()
    }
}
