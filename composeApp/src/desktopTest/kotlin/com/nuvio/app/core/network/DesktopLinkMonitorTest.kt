package com.nuvio.app.core.network

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopLinkMonitorTest {

    @Test
    fun `adapters that stay up through a pulled cable are ignored`() {
        listOf(
            "vEthernet (Default Switch)",
            "Hyper-V Virtual Ethernet Adapter",
            "VirtualBox Host-Only Ethernet Adapter",
            "TAP-Windows Adapter V9",
            "Bluetooth Device (Personal Area Network)",
            "utun3",
            "docker0",
        ).forEach { assertTrue(DesktopLinkMonitor.isVirtualAdapterName(it), it) }
    }

    @Test
    fun `real adapters count`() {
        listOf(
            "Intel(R) Wi-Fi 6 AX201 160MHz wlan0",
            "Realtek PCIe GbE Family Controller eth0",
            "en0",
        ).forEach { assertFalse(DesktopLinkMonitor.isVirtualAdapterName(it), it) }
    }

    @Test
    fun `only routable addresses mean a link`() {
        assertTrue(DesktopLinkMonitor.isRoutableAddress(InetAddress.getByName("192.168.100.12")))
        assertTrue(DesktopLinkMonitor.isRoutableAddress(InetAddress.getByName("2a02:aa1::5")))
        assertFalse(DesktopLinkMonitor.isRoutableAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(DesktopLinkMonitor.isRoutableAddress(InetAddress.getByName("169.254.10.2")))
        assertFalse(DesktopLinkMonitor.isRoutableAddress(InetAddress.getByName("fe80::1")))
    }
}
