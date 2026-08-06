package com.nuvio.app.core.network

actual object NetworkQualityPlatform {
    actual fun current(): PlatformNetworkQuality = PlatformNetworkQuality(
        connectionType = NetworkConnectionType.ETHERNET,
        isMetered = false,
        networkId = "desktop:ethernet",
    )
}
