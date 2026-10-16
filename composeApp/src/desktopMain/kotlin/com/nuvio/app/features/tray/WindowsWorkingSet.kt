package com.nuvio.app.features.tray

import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

/**
 * Hands the pages Nuvio is not touching back to Windows while it sits in the tray.
 *
 * `K32EmptyWorkingSet` on our own process: Windows moves every page out of RAM to the standby list,
 * and only the ones touched again come back. Stage T0 measured it: hidden, after the UI is torn down
 * and the heap collected, the private working set went from ~437 MB to ~135 MB and stayed there. The
 * memory is still committed (it shows as private bytes, not RAM); it is not freed, only not resident.
 * Tray apps commonly do this.
 *
 * Called through the JDK's foreign-function API (the shipped runtime is JBR 25), so no JNA and no
 * change to the native player bridge. Any failure is ignored: this is an optimisation, never required.
 */
internal object WindowsWorkingSet {
    private val emptyWorkingSet by lazy {
        runCatching {
            val linker = Linker.nativeLinker()
            val kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global())
            val currentProcess = linker.downcallHandle(
                kernel32.find("GetCurrentProcess").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS),
            )
            val empty = linker.downcallHandle(
                kernel32.find("K32EmptyWorkingSet").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
            )
            val process = currentProcess.invokeWithArguments() as MemorySegment
            return@runCatching { (empty.invokeWithArguments(process) as Int) != 0 }
        }.getOrNull()
    }

    fun trim(): Boolean {
        if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return false
        return runCatching { emptyWorkingSet?.invoke() ?: false }.getOrDefault(false)
    }
}
