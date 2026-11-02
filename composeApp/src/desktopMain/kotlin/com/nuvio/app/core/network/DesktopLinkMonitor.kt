package com.nuvio.app.core.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Desktop's half of [PlatformLink]: Windows, macOS and Linux all expose the same interface table
 * to the JVM, so one poll serves all three. There is no change callback the JVM can reach, and
 * three seconds is far shorter than the next foreground or manual retry, which was the only thing
 * that told desktop it had gone offline before.
 *
 * Virtual adapters are ignored because they stay up with an address when the real link has gone:
 * a Hyper-V switch or a VPN tap would otherwise report Up through a pulled cable. Erring the other
 * way - calling a real-but-unusual adapter virtual - only costs the fast path; the HTTP probe
 * still finds the truth.
 */
object DesktopLinkMonitor {
    private const val POLL_INTERVAL_MS = 3_000L

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nuvio-link-monitor").apply { isDaemon = true }
    }

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        executor.scheduleWithFixedDelay(
            { runCatching { PlatformLink.report(up = hasUsableLink()) } },
            0L,
            POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    internal fun hasUsableLink(): Boolean =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { networkInterface ->
            runCatching {
                networkInterface.isUp &&
                    !networkInterface.isLoopback &&
                    !networkInterface.isVirtual &&
                    !isVirtualAdapterName(networkInterface.displayName.orEmpty() + " " + networkInterface.name.orEmpty()) &&
                    networkInterface.inetAddresses.toList().any(::isRoutableAddress)
            }.getOrDefault(false)
        }

    private val virtualAdapterMarkers = listOf(
        "virtual", "vethernet", "hyper-v", "vmware", "virtualbox", "loopback", "tap-", "tunnel",
        "teredo", "isatap", "bluetooth", "docker", "wsl", "vboxnet", "utun", "awdl", "llw",
    )

    internal fun isVirtualAdapterName(name: String): Boolean {
        val normalized = name.lowercase()
        return virtualAdapterMarkers.any { it in normalized }
    }

    internal fun isRoutableAddress(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isAnyLocalAddress
        is Inet6Address -> !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isAnyLocalAddress
        else -> false
    }
}
