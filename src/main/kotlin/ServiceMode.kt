import kotlinx.coroutines.*
import javax.sound.sampled.Mixer

object ServiceMode {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var streamingCore: StreamingCore

    fun start() {
        println("==========================================")
        println("   WiFi Audio Streaming - SERVICE MODE    ")
        println("==========================================")

        // 1. Setup Firewall
        println("[1/4] Configuring Windows Firewall...")
        FirewallManager.setupFirewall()

        // 2. Load Settings
        println("[2/4] Loading settings...")
        val settings = SettingsRepository.loadSettings()
        
        streamingCore = StreamingCore(scope)

        // 3. Selection of Audio Device
        println("[3/4] Selecting audio source...")
        val inputDevices = StreamingCore.findAvailableInputMixers()
        
        // Try to find VB-CABLE or use first available
        val selectedDevice = inputDevices.find { it.name.contains("CABLE Input", ignoreCase = true) }
            ?: inputDevices.firstOrNull()

        if (selectedDevice == null) {
            System.err.println("CRITICAL ERROR: No audio input devices found!")
            return
        }
        println("Selected Device: ${selectedDevice.name}")

        // 4. Start Server
        println("[4/4] Starting server instance...")
        val port = settings.streamingPort.toIntOrNull() ?: 9090
        val micPort = settings.micPort.toIntOrNull() ?: 9092
        
        // We use Multicast by default for service mode as it's more flexible
        val isMulticast = true 

        // Optimizations for Mic-only environments
        var audioSettings = settings.audio
        var gain = 1.0f

        val isMic = selectedDevice.name.contains("Microphone", ignoreCase = true) || 
                    selectedDevice.name.contains("麦克风", ignoreCase = true)
        
        if (isMic) {
            println("[INFO] Microphone detected. Optimizing: Mono mode + Digital Gain (2.0x)")
            audioSettings = audioSettings.copy(channels = 1)
            gain = 2.0f // Double the volume for quiet PC microphones
        }

        streamingCore.launchServerInstance(
            audioSettings = audioSettings,
            port = port,
            isMulticast = isMulticast,
            selectedMixerInfo = selectedDevice,
            micOutputMixerInfo = null, // In service mode, we focus on sending PC audio
            micPort = micPort,
            gain = gain,
            onStatusUpdate = { key, args ->
                val message = Strings.get(key, *args)
                println("[STATUS] $message")
            }
        )

        println("\nService is running. Press Ctrl+C to stop.")
        
        // Keep the application alive
        runBlocking {
            while (true) {
                delay(1000)
            }
        }
    }
}
