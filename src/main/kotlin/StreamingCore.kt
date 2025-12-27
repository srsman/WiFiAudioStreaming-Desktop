import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import javax.sound.sampled.*
import java.net.InetSocketAddress as JInetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StreamingCore(private val scope: CoroutineScope) {
    private var streamingJob: Job? = null
    private var listeningJob: Job? = null
    private var broadcastingJob: Job? = null
    private var serverAudioLine: TargetDataLine? = null
    private var micReceiverJob: Job? = null
    var webServer: WebStreamingServer? = null
    var currentAudioSettings: AudioSettings_V1? = null
    var currentPort: Int = 9090
    var currentIsMulticast: Boolean = true
    var currentMicPort: Int = 9092
    var currentOnStatusUpdate: ((String, Array<out Any>) -> Unit)? = null

    companion object {
        const val DISCOVERY_PORT = 9091
        const val CLIENT_HELLO_MESSAGE = "HELLO_FROM_CLIENT"
        const val MULTICAST_GROUP_IP = "239.255.0.1"
        const val DISCOVERY_MESSAGE = "WIFI_AUDIO_STREAMER_DISCOVERY"

        fun findAvailableOutputMixers(): List<Mixer.Info> {
            return AudioSystem.getMixerInfo()
                .filter { mixerInfo ->
                    !mixerInfo.name.startsWith("Port", ignoreCase = true) &&
                            AudioSystem.getMixer(mixerInfo).isLineSupported(Line.Info(SourceDataLine::class.java))
                }
        }

        fun findAvailableInputMixers(): List<Mixer.Info> {
            return AudioSystem.getMixerInfo()
                .filter { mixerInfo ->
                    !mixerInfo.name.startsWith("Port", ignoreCase = true) &&
                            AudioSystem.getMixer(mixerInfo).isLineSupported(Line.Info(TargetDataLine::class.java))
                }
        }
    }

    fun beginDeviceDiscovery(onDeviceFound: (hostname: String, serverInfo: ServerInfo) -> Unit) {
        if (listeningJob?.isActive == true) return
        listeningJob = scope.launch(Dispatchers.IO) {
            var socket: MulticastSocket? = null
            try {
                val localIps = NetworkInterface.getNetworkInterfaces().toList()
                    .flatMap { it.inetAddresses.toList() }
                    .map { it.hostAddress }
                    .toSet()

                val groupAddress = InetAddress.getByName(MULTICAST_GROUP_IP)
                socket = MulticastSocket(DISCOVERY_PORT).apply {
                    joinGroup(groupAddress)
                    soTimeout = 5000
                }

                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isActive) {
                    try {
                        socket.receive(packet)
                        val remoteIp = packet.address.hostAddress
                        val message = String(packet.data, 0, packet.length).trim()

                        if (remoteIp != null && remoteIp !in localIps && message.startsWith(DISCOVERY_MESSAGE)) {
                            val parts = message.split(";")
                            if (parts.size == 4) {
                                val hostname = parts[1]
                                val isMulticast = parts[2].equals("MULTICAST", ignoreCase = true)
                                val port = parts[3].toIntOrNull() ?: continue
                                onDeviceFound(hostname, ServerInfo(remoteIp, isMulticast, port))
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        continue
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) println("Listening Error: ${e.message}")
            } finally {
                socket?.leaveGroup(InetAddress.getByName(MULTICAST_GROUP_IP))
                socket?.close()
            }
        }
    }

    fun endDeviceDiscovery() {
        listeningJob?.cancel()
    }

    fun startAnnouncingPresence(isMulticast: Boolean, port: Int) {
        broadcastingJob?.cancel()
        broadcastingJob = scope.launch(Dispatchers.IO) {
            val hostname = try { InetAddress.getLocalHost().hostName } catch (e: Exception) { "Desktop-PC" }
            val mode = if (isMulticast) "MULTICAST" else "UNICAST"
            val message = "$DISCOVERY_MESSAGE;$hostname;$mode;$port"

            DatagramSocket().use { socket ->
                val groupAddress = InetAddress.getByName(MULTICAST_GROUP_IP)
                while (isActive) {
                    val packet = DatagramPacket(message.toByteArray(), message.length, groupAddress, DISCOVERY_PORT)
                    socket.send(packet)
                    delay(3000)
                }
            }
        }
    }

    fun stopAnnouncingPresence() {
        broadcastingJob?.cancel()
    }

    private fun CoroutineScope.launchMicReceiver(
        audioSettings: AudioSettings_V1, isMulticast: Boolean, micOutputMixerInfo: Mixer.Info, micPort: Int
    ) = launch(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        val micMixer = AudioSystem.getMixer(micOutputMixerInfo)
        val format = audioSettings.toAudioFormat()
        val lineInfo = DataLine.Info(SourceDataLine::class.java, format)
        if (!micMixer.isLineSupported(lineInfo)) return@launch

        val micOutputLine = micMixer.getLine(lineInfo) as SourceDataLine
        micOutputLine.open(format, audioSettings.bufferSize * 4)
        micOutputLine.start()

        try {
            val buffer = ByteArray(audioSettings.bufferSize * 2)
            val packet = DatagramPacket(buffer, buffer.size)

            socket = if (isMulticast) {
                MulticastSocket(micPort).apply { joinGroup(InetAddress.getByName(MULTICAST_GROUP_IP)) }
            } else {
                DatagramSocket(micPort)
            }

            while (isActive) {
                socket.receive(packet)
                if (packet.length > 0) micOutputLine.write(packet.data, 0, packet.length)
            }
        } catch (e: Exception) {
            if (e !is CancellationException) println("Mic receiving error: ${e.message}")
        } finally {
            socket?.close()
            micOutputLine.drain(); micOutputLine.stop(); micOutputLine.close()
        }
    }

    private fun CoroutineScope.launchMicSender(
        audioSettings: AudioSettings_V1, serverInfo: ServerInfo, micInputMixerInfo: Mixer.Info, micPort: Int
    ) = launch(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        val micMixer = AudioSystem.getMixer(micInputMixerInfo)
        val format = audioSettings.toAudioFormat()
        val lineInfo = DataLine.Info(TargetDataLine::class.java, format)
        if (!micMixer.isLineSupported(lineInfo)) return@launch

        val micInputLine = micMixer.getLine(lineInfo) as TargetDataLine
        micInputLine.open(format, audioSettings.bufferSize)
        micInputLine.start()

        try {
            socket = DatagramSocket()
            val destinationAddress = if (serverInfo.isMulticast) {
                InetAddress.getByName(MULTICAST_GROUP_IP)
            } else {
                InetAddress.getByName(serverInfo.ip)
            }
            val buffer = ByteArray(audioSettings.bufferSize)
            while (isActive) {
                val bytesRead = micInputLine.read(buffer, 0, buffer.size)
                if (bytesRead > 0) {
                    val packet = DatagramPacket(buffer, bytesRead, destinationAddress, micPort)
                    socket.send(packet)
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) println("Mic sending error: ${e.message}")
        } finally {
            socket?.close()
            micInputLine.stop(); micInputLine.close()
        }
    }


    fun launchServerInstance(
        audioSettings: AudioSettings_V1,
        port: Int,
        isMulticast: Boolean,
        selectedMixerInfo: Mixer.Info?,
        micOutputMixerInfo: Mixer.Info?,
        micPort: Int,
        gain: Float = 1.0f,
        onStatusUpdate: (key: String, args: Array<out Any>) -> Unit
    ) {
        if (micOutputMixerInfo != null) {
            micReceiverJob = scope.launchMicReceiver(audioSettings, isMulticast, micOutputMixerInfo, micPort)
        }
        
        this.currentAudioSettings = audioSettings
        this.currentPort = port
        this.currentIsMulticast = isMulticast
        this.currentMicPort = micPort
        this.currentOnStatusUpdate = onStatusUpdate

        startAnnouncingPresence(isMulticast, port)
        
        if (webServer == null) {
            webServer = WebStreamingServer(8080, this) { deviceIndex ->
                scope.launch {
                    try {
                        val mixers = AudioSystem.getMixerInfo()
                        if (deviceIndex in mixers.indices) {
                            val newMixerInfo = mixers[deviceIndex]
                            println("[CORE] Switching to $newMixerInfo...")
                            
                            stopAudioOnly()
                            delay(1000) // Ensure hardware release
                            
                            // Re-calculate optimizations
                            var newSettings = currentAudioSettings ?: audioSettings
                            var newGain = 1.0f
                            val isMic = newMixerInfo.name.contains("Mic", true) || newMixerInfo.name.contains("麦克风", true)
                            if (isMic) {
                                newSettings = newSettings.copy(channels = 1)
                                newGain = 2.0f
                            }
                            
                            // Start new stream
                            launchServerInstance(
                                newSettings, currentPort, currentIsMulticast,
                                newMixerInfo, micOutputMixerInfo, currentMicPort,
                                newGain, currentOnStatusUpdate ?: onStatusUpdate
                            )
                        }
                    } catch (e: Exception) {
                        println("[ERROR] Failed to switch source: ${e.message}")
                    }
                }
            }
            webServer?.start(audioSettings)
        }

        streamingJob = scope.launch(Dispatchers.IO) {
            var audioLine: TargetDataLine? = null
            try {
                val systemAudioDeviceInfo = selectedMixerInfo ?: run {
                    onStatusUpdate("status_error_no_device", emptyArray())
                    return@launch
                }
                val audioMixer = AudioSystem.getMixer(systemAudioDeviceInfo)
                val format = audioSettings.toAudioFormat()
                val lineInfo = DataLine.Info(TargetDataLine::class.java, format)
                if (!AudioSystem.isLineSupported(lineInfo)) {
                    onStatusUpdate("status_error_unsupported_format", emptyArray())
                    return@launch
                }
                val frameSize = format.frameSize
                val adjustedBufferSize = (audioSettings.bufferSize / frameSize) * frameSize
                if (adjustedBufferSize <= 0) {
                    onStatusUpdate("status_error_invalid_buffer", emptyArray())
                    return@launch
                }
                audioLine = audioMixer.getLine(lineInfo) as? TargetDataLine
                serverAudioLine = audioLine
                audioLine?.open(format, adjustedBufferSize)
                audioLine?.start()
                if (audioLine == null || !audioLine.isOpen) {
                    onStatusUpdate("status_error_critical_line", emptyArray())
                    serverAudioLine = null
                    return@launch
                }

                if (isMulticast) {
                    onStatusUpdate("status_multicast_streaming", arrayOf(port))
                    MulticastSocket().use { socket ->
                        val group = InetAddress.getByName(MULTICAST_GROUP_IP)
                        val buffer = ByteArray(adjustedBufferSize)
                        while (isActive) {
                            val bytesRead = audioLine.read(buffer, 0, buffer.size)
                            if (bytesRead > 0) {
                                if (gain != 1.0f) applyGain(buffer, bytesRead, gain)
                                val packet = DatagramPacket(buffer, bytesRead, group, port)
                                socket.send(packet)
                                webServer?.broadcastAudio(buffer.copyOf(bytesRead))
                            } else if (bytesRead < 0) break
                        }
                    }
                } else { // Unicast
                    onStatusUpdate("status_server_waiting", arrayOf(port))
                    val localAddress = InetSocketAddress("0.0.0.0", port)
                    aSocket(SelectorManager(Dispatchers.IO)).udp().bind(localAddress) { reuseAddress = true }.use { socket ->
                        val clientDatagram = socket.receive()
                        if (clientDatagram.packet.readText().trim() == CLIENT_HELLO_MESSAGE) {
                            val clientAddress = clientDatagram.address
                            onStatusUpdate("status_client_connected", arrayOf(clientAddress))
                            stopAnnouncingPresence()

                            val ackPacket = buildPacket { writeText("HELLO_ACK") }
                            socket.send(Datagram(ackPacket, clientAddress))

                            val buffer = ByteArray(adjustedBufferSize)
                            while (isActive) {
                                val bytesRead = audioLine.read(buffer, 0, buffer.size)
                                if (bytesRead > 0) {
                                    if (gain != 1.0f) applyGain(buffer, bytesRead, gain)
                                    val packet = buildPacket { writeFully(buffer, 0, bytesRead) }
                                    socket.send(Datagram(packet, clientAddress))
                                    webServer?.broadcastAudio(buffer.copyOf(bytesRead))
                                } else if (bytesRead < 0) break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) onStatusUpdate("status_error_server", arrayOf(e.message ?: "Unknown"))
            } finally {
                stopAnnouncingPresence()
                onStatusUpdate("status_server_stopped", emptyArray())
                audioLine?.stop(); audioLine?.close()
                serverAudioLine = null
                webServer?.stop()
                webServer = null
            }
        }
    }

    fun launchClientInstance(
        audioSettings: AudioSettings_V1,
        serverInfo: ServerInfo,
        selectedMixerInfo: Mixer.Info,
        sendMicrophone: Boolean,
        micInputMixerInfo: Mixer.Info?,
        micPort: Int,
        onStatusUpdate: (key: String, args: Array<out Any>) -> Unit
    ) {
        if (sendMicrophone && micInputMixerInfo != null) {
            micReceiverJob = scope.launchMicSender(audioSettings, serverInfo, micInputMixerInfo, micPort)
        }
        streamingJob = scope.launch(Dispatchers.IO) {
            var sourceDataLine: SourceDataLine? = null
            try {
                if (!serverInfo.isMulticast) { // Unicast
                    val remoteAddress = InetSocketAddress(serverInfo.ip, serverInfo.port)
                    aSocket(SelectorManager(Dispatchers.IO)).udp().bind().use { socket ->
                        onStatusUpdate("status_contacting_server", arrayOf(remoteAddress))
                        val helloPacket = buildPacket { writeText(CLIENT_HELLO_MESSAGE) }
                        socket.send(Datagram(helloPacket, remoteAddress))

                        onStatusUpdate("status_waiting_ack", emptyArray())
                        val ackDatagram = withTimeout(5000) { socket.receive() }
                        if (ackDatagram.packet.readText().trim() != "HELLO_ACK") {
                            onStatusUpdate("status_handshake_failed", emptyArray())
                            return@use
                        }

                        onStatusUpdate("status_connected_streaming_from", arrayOf(remoteAddress))
                        sourceDataLine = prepareSourceDataLine(selectedMixerInfo, audioSettings)
                        sourceDataLine?.start()

                        val buffer = ByteArray(audioSettings.bufferSize * 2)
                        while (isActive) {
                            val datagram = socket.receive()
                            val bytesRead = datagram.packet.readAvailable(buffer)
                            if (bytesRead > 0) sourceDataLine?.write(buffer, 0, bytesRead)
                        }
                    }
                } else { // Multicast
                    onStatusUpdate("status_joining_multicast", arrayOf(serverInfo.port))
                    val groupAddress = InetAddress.getByName(MULTICAST_GROUP_IP)
                    MulticastSocket(serverInfo.port).use { socket ->
                        socket.joinGroup(groupAddress)
                        sourceDataLine = prepareSourceDataLine(selectedMixerInfo, audioSettings)
                        sourceDataLine?.start()

                        onStatusUpdate("status_multicast_streaming", arrayOf(serverInfo.port))
                        val buffer = ByteArray(audioSettings.bufferSize * 2)
                        val packet = DatagramPacket(buffer, buffer.size)
                        while (isActive) {
                            socket.receive(packet)
                            if (packet.length > 0) sourceDataLine?.write(packet.data, 0, packet.length)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                onStatusUpdate("status_server_no_response", emptyArray())
            } catch (e: Exception) {
                if (e !is CancellationException) onStatusUpdate("status_error_client", arrayOf(e.message ?: "Unknown"))
            } finally {
                onStatusUpdate("status_streaming_ended", emptyArray())
                sourceDataLine?.drain(); sourceDataLine?.stop(); sourceDataLine?.close()
            }
        }
    }

    private fun prepareSourceDataLine(mixerInfo: Mixer.Info, audioSettings: AudioSettings_V1): SourceDataLine? {
        val mixer = AudioSystem.getMixer(mixerInfo)
        val format = audioSettings.toAudioFormat()
        val dataLineInfo = DataLine.Info(SourceDataLine::class.java, format)
        if (!mixer.isLineSupported(dataLineInfo)) return null

        val frameSize = format.frameSize
        val adjustedBufferSize = (audioSettings.bufferSize / frameSize) * frameSize
        val sourceDataLine = mixer.getLine(dataLineInfo) as SourceDataLine
        sourceDataLine.open(format, adjustedBufferSize * 4)
        return sourceDataLine
    }

    suspend fun stopCurrentStream() {
        stopAnnouncingPresence()
        streamingJob?.cancelAndJoin()
        micReceiverJob?.cancelAndJoin()
        serverAudioLine?.stop(); serverAudioLine?.close()
        webServer?.stop()
        streamingJob = null; micReceiverJob = null; serverAudioLine = null; webServer = null
    }

    private suspend fun stopAudioOnly() {
        stopAnnouncingPresence()
        streamingJob?.cancelAndJoin()
        micReceiverJob?.cancelAndJoin()
        serverAudioLine?.stop(); serverAudioLine?.close()
        streamingJob = null; micReceiverJob = null; serverAudioLine = null
    }

    private fun applyGain(buffer: ByteArray, bytesRead: Int, gain: Float) {
        val shortBuffer = ByteBuffer.wrap(buffer, 0, bytesRead)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        
        for (i in 0 until shortBuffer.remaining()) {
            val original = shortBuffer.get(i)
            val boosted = (original * gain).toInt().coerceIn(-32768, 32767).toShort()
            shortBuffer.put(i, boosted)
        }
    }
}
