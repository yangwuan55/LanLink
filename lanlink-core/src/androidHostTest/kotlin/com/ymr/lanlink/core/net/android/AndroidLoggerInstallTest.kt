package com.ymr.lanlink.core.net.android

import com.ymr.lanlink.core.platform.AndroidLogger
import com.ymr.lanlink.core.platform.logger
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins M-1: constructing [AndroidLanNetworkFactory] must install [AndroidLogger]
 * as the global logger so lanlink-core logs reach logcat (not the println default).
 * Constructing the factory only triggers the installer; it does not call Log.x,
 * so this stays a plain JVM host test (no Robolectric needed).
 */
class AndroidLoggerInstallTest {

    @Test
    fun `constructing the factory installs AndroidLogger as the global logger`() {
        AndroidLanNetworkFactory()
        assertTrue(
            "global logger should be AndroidLogger after factory construction",
            logger is AndroidLogger
        )
    }
}
