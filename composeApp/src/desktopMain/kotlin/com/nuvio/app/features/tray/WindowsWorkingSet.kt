package com.nuvio.app.features.tray

import com.nuvio.app.features.player.desktop.DesktopHostOs

/**
 * Hands the pages Nuvio is not touching back to Windows while it sits in the tray.
 *
 * `K32EmptyWorkingSet` on our own process: Windows moves every page out of RAM to the standby list,
 * and only the ones touched again come back. Stage T0 measured it: hidden, after the UI is torn down
 * and the heap collected, the private working set went from ~437 MB to ~135 MB and stayed there. The
 * memory is still committed (it shows as private bytes, not RAM); it is not freed, only not resident.
 * Tray apps commonly do this. Any failure is ignored: this is an optimisation, never required.
 */
internal object WindowsWorkingSet {
    fun trim(): Boolean {
        if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return false
        return WindowsForeign.emptyWorkingSet()
    }
}
