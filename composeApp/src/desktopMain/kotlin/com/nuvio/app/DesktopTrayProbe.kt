package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.nuvio.app.features.social.SocialRepository
import java.io.File
import java.lang.management.ManagementFactory
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SPIKE (Social V2 stage T0, branch `claude/tray-memory-probe`, never merged): measures what Nuvio
 * costs while hidden to the tray. With `-Dnuvio.trayProbe=<open seconds>` the window stays up that
 * long, is then removed from the composition (the tray icon keeps `application {}` alive), the Coil
 * memory cache is cleared and the heap collected, and the process sits hidden for
 * `nuvio.trayProbe.hidden` seconds (default 240) before exiting. Every 5 s it appends a heap sample
 * to `nuvio.trayProbe.log`; the working set and private bytes are sampled from outside.
 */
internal object DesktopTrayProbe {
    val openSeconds: Long? = System.getProperty("nuvio.trayProbe")?.toLongOrNull()
    private val hiddenSeconds: Long = System.getProperty("nuvio.trayProbe.hidden")?.toLongOrNull() ?: 240L
    private val logFile = File(System.getProperty("nuvio.trayProbe.log") ?: "tray-probe.csv")

    @Volatile
    private var phase = "open"

    @Composable
    fun ApplicationScope.Run(icon: Painter, onHide: () -> Unit) {
        val open = openSeconds ?: return
        Tray(icon = icon, tooltip = "Nuvio Z (tray probe)") {
            Item("Quit", onClick = ::exitApplication)
        }
        LaunchedEffect(Unit) {
            launch(Dispatchers.IO) {
                logFile.writeText("time,phase,heap_used_mb,heap_committed_mb,nonheap_committed_mb\n")
                while (isActive) {
                    sample()
                    delay(5_000)
                }
            }
            // The open phase is timed from Home, not from launch: the Social runtime activates only
            // once the profile gate has been passed.
            SocialRepository.uiState.first { it.activeProfileId != null }
            phase = "home"
            delay(open * 1_000)
            phase = "hiding"
            onHide()
            delay(2_000)
            SingletonImageLoader.get(PlatformContext.INSTANCE).memoryCache?.clear()
            withContext(Dispatchers.Default) {
                repeat(2) {
                    System.gc()
                    delay(1_000)
                }
            }
            phase = "hidden"
            delay(hiddenSeconds * 1_000)
            sample()
            exitApplication()
        }
    }

    private fun sample() {
        val memory = ManagementFactory.getMemoryMXBean()
        val heap = memory.heapMemoryUsage
        val nonHeap = memory.nonHeapMemoryUsage
        logFile.appendText(
            "${Instant.now()},$phase,${heap.used.mb()},${heap.committed.mb()},${nonHeap.committed.mb()}\n",
        )
    }

    private fun Long.mb(): Long = this / (1024 * 1024)
}
