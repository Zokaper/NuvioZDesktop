package com.nuvio.app.core.ui

import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

@OptIn(DelicateCoilApi::class)
class AppImageLoaderStartupTest {
    @Test
    fun `hidden startup cache access and repeated window opens keep the configured loader`() {
        SingletonImageLoader.reset()
        try {
            // Reproduce the old order: the hidden tray trim creates Coil's default loader.
            SingletonImageLoader.get(PlatformContext.INSTANCE).memoryCache?.clear()
            assertFailsWith<IllegalStateException> { configureAppImageLoader() }
            SingletonImageLoader.reset()

            // Production now registers before startHidden, even with no Compose window yet.
            configureAppImageLoader()
            val loader = SingletonImageLoader.get(PlatformContext.INSTANCE)
            repeat(10) {
                loader.memoryCache?.clear()
                configureAppImageLoader()
                assertSame(loader, SingletonImageLoader.get(PlatformContext.INSTANCE))
            }
        } finally {
            SingletonImageLoader.reset()
        }
    }
}
