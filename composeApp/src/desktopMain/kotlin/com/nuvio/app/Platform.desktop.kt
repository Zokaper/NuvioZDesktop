package com.nuvio.app

class DesktopPlatform : Platform {
    override val name: String = "Desktop ${System.getProperty("os.name").orEmpty()}".trim()
}

actual fun getPlatform(): Platform = DesktopPlatform()

internal actual val isIos: Boolean = false
internal actual val isDesktop: Boolean = true
internal actual val isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

actual fun platformDisplayMaxHeight(): Int? = runCatching {
    if (java.awt.GraphicsEnvironment.isHeadless()) return null
    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        .screenDevices
        .mapNotNull { device ->
            device.displayMode?.height?.takeIf { it > 0 }
                ?: device.defaultConfiguration?.bounds?.height?.takeIf { it > 0 }
        }
        .maxOrNull()
}.getOrNull()

