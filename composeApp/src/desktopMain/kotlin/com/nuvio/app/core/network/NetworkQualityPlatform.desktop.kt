package com.nuvio.app.core.network

import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

private const val NETWORK_PROBE_CACHE_MS = 30_000L

actual object NetworkQualityPlatform {
    private var cachedAtMs: Long = 0L
    private var cached: PlatformNetworkQuality? = null

    actual fun current(): PlatformNetworkQuality {
        val now = System.currentTimeMillis()
        cached?.takeIf { now - cachedAtMs < NETWORK_PROBE_CACHE_MS }?.let { return it }
        return probeWindowsNetworkQuality() ?: fallbackNetworkQuality().also {
            cached = it
            cachedAtMs = now
        }
    }

    private fun probeWindowsNetworkQuality(): PlatformNetworkQuality? {
        if (!System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)) return null
        val script = """
            ${'$'}type = [Windows.Networking.Connectivity.NetworkInformation,Windows.Networking.Connectivity,ContentType=WindowsRuntime]
            ${'$'}profile = ${'$'}type::GetInternetConnectionProfile()
            if (${ '$' }null -eq ${'$'}profile) { 'OFFLINE|false|offline' } else {
              ${'$'}kind = if (${ '$' }profile.IsWlanConnectionProfile) { 'WIFI' } elseif (${ '$' }profile.IsWwanConnectionProfile) { 'CELLULAR' } else { 'ETHERNET' }
              ${'$'}cost = ${'$'}profile.GetConnectionCost().NetworkCostType.ToString()
              ${'$'}metered = (${ '$' }cost -ne 'Unrestricted' -and ${ '$' }cost -ne 'Unknown')
              "${'$'}kind|${'$'}metered|${'$'}(${ '$' }profile.ProfileName)"
            }
        """.trimIndent()
        return runCatching {
            val process = ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-Command", script,
            ).redirectErrorStream(true).start()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            parseWindowsNetworkProbe(process.inputStream.bufferedReader().readText())
        }.getOrNull()?.also {
            cached = it
            cachedAtMs = System.currentTimeMillis()
        }
    }
}

internal fun parseWindowsNetworkProbe(output: String): PlatformNetworkQuality? {
    val parts = output.lineSequence().map(String::trim).lastOrNull { it.count { char -> char == '|' } >= 2 }
        ?.split('|', limit = 3) ?: return null
    val type = runCatching { NetworkConnectionType.valueOf(parts[0].uppercase()) }.getOrNull() ?: return null
    val metered = parts[1].equals("true", ignoreCase = true)
    val identity = parts[2].trim().ifBlank { type.name.lowercase() }
    return PlatformNetworkQuality(
        connectionType = type,
        isMetered = metered,
        networkId = "desktop:${identity.hashCode().toUInt().toString(16)}",
    )
}

private fun fallbackNetworkQuality(): PlatformNetworkQuality {
    val active = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().firstOrNull { it.isUp && !it.isLoopback }
    }.getOrNull()
    if (active == null) {
        return PlatformNetworkQuality(NetworkConnectionType.OFFLINE, false, "desktop:offline")
    }
    val name = "${active.name} ${active.displayName}".lowercase()
    val type = if (listOf("wi-fi", "wifi", "wlan", "wireless").any(name::contains)) {
        NetworkConnectionType.WIFI
    } else {
        NetworkConnectionType.ETHERNET
    }
    return PlatformNetworkQuality(type, false, "desktop:${active.name.hashCode().toUInt().toString(16)}")
}
