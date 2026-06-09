package com.ymr.lancomm.net

import com.ymr.lancomm.data.discovery.DiscoveredPeer
import kotlinx.coroutines.flow.StateFlow

/**
 * Scans the LAN for advertised peers (e.g. by listening for UDP broadcasts) and
 * exposes the discovered set as a flow.
 */
interface DiscoveryScanner {
    /** Current set of discovered peers. */
    val discoveredPeers: StateFlow<List<DiscoveredPeer>>

    /** Begins scanning. */
    fun start()

    /** Stops scanning and releases resources. */
    fun stop()
}
