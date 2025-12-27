import java.io.File
import java.util.Locale

object FirewallManager {
    private const val RULE_NAME_UDP = "WiFiAudioStreaming-UDP-Global"
    private const val RULE_NAME_TCP = "WiFiAudioStreaming-TCP-Global"
    
    fun setupFirewall() {
        if (!isWindows()) return
        
        try {
            // Setup UDP Rule (Port-based, not program-path based for portability)
            if (!isRuleDefined(RULE_NAME_UDP)) {
                addPortRule(RULE_NAME_UDP, "UDP", "9090,9091,9092")
            }

            // Setup TCP Rule (Port-based for Web Interface)
            if (!isRuleDefined(RULE_NAME_TCP)) {
                addPortRule(RULE_NAME_TCP, "TCP", "8080")
            }
        } catch (e: Exception) {
            System.err.println("Error setting up firewall: ${e.message}")
        }
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").lowercase(Locale.ENGLISH).contains("win")
    }

    private fun isRuleDefined(ruleName: String): Boolean {
        val result = executeCommand("netsh advfirewall firewall show rule name=\"$ruleName\"")
        return result.exitCode == 0 && result.output.isNotEmpty()
    }

    private fun addPortRule(ruleName: String, protocol: String, ports: String) {
        // We removed the 'program' parameter to allow the app to work from any directory
        val command = """
            netsh advfirewall firewall add rule 
            name="$ruleName" 
            dir=in 
            action=allow 
            protocol=$protocol 
            localport=$ports 
            enable=yes 
            profile=any
        """.trimIndent().replace("\n", " ")
        
        val result = executeCommand(command)
        if (result.exitCode == 0) {
            println("Global firewall rule '$ruleName' added successfully for port(s) $ports.")
        } else {
            System.err.println("Failed to add global firewall rule '$ruleName': ${result.error}")
        }
    }

    private fun executeCommand(cmd: String): CommandResult {
        return try {
            val process = Runtime.getRuntime().exec(cmd)
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            CommandResult(exitCode, output.trim(), error.trim())
        } catch (e: Exception) {
            CommandResult(-1, "", e.message ?: "Unknown error")
        }
    }

    private data class CommandResult(val exitCode: Int, val output: String, val error: String)
}
