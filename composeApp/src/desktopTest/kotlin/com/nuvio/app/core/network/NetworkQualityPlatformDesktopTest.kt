package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkQualityPlatformDesktopTest {
    @Test
    fun parsesMeteredWifiProbe() {
        val result = parseWindowsNetworkProbe("WIFI|True|Phone hotspot")!!
        assertEquals(NetworkConnectionType.WIFI, result.connectionType)
        assertTrue(result.isMetered)
        assertTrue(result.networkId.startsWith("desktop:"))
    }

    @Test
    fun ignoresPowerShellNoiseAndRejectsMalformedOutput() {
        assertEquals(
            NetworkConnectionType.ETHERNET,
            parseWindowsNetworkProbe("warning\nETHERNET|False|Office")?.connectionType,
        )
        assertNull(parseWindowsNetworkProbe("permission denied"))
    }

    @Test
    fun currentReturnsPromptlyWithoutBlockingCaller() {
        val start = System.currentTimeMillis()
        val quality = NetworkQualityPlatform.current()
        val elapsed = System.currentTimeMillis() - start
        assertTrue(elapsed < 200, "current() took ${elapsed}ms; should return immediately without blocking")
        assertTrue(quality.networkId.isNotBlank())
    }
}
