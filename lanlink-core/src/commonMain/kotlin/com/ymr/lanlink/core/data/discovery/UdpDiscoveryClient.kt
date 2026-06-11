package com.ymr.lanlink.core.data.discovery

import com.ymr.lanlink.core.net.DiscoveryScanner
import com.ymr.lanlink.core.platform.ioDispatcher
import com.ymr.lanlink.core.platform.logger
import com.ymr.lanlink.core.platform.nowMillis
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.io.readByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class UdpDiscoveryClient(
    private val ignoredServicePorts: Set<Int> = emptySet()
) : DiscoveryScanner {

    private var selector: SelectorManager? = null
    private var socket: io.ktor.network.sockets.BoundDatagramSocket? = null
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())
    private var isRunning = false

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private val _state = MutableStateFlow<UdpDiscoveryState>(UdpDiscoveryState.Idle)
    val state: StateFlow<UdpDiscoveryState> = _state.asStateFlow()

    // Live peer table with identity-based dedup + TTL expiry. Guarded by [peersMutex]
    // since handleMessage and the cleanup loop both mutate it.
    private val peers = PeerTable(PEER_TTL_MS)
    private val peersMutex = Mutex()

    companion object {
        private const val TAG = "UdpDiscoveryClient"
        private const val LISTEN_PORT = 45678
        // 3x the advertiser's 2000ms broadcast interval: tolerate a couple of lost
        // datagrams before evicting a peer that has actually gone away.
        private const val PEER_TTL_MS = 6000L
        private const val CLEANUP_INTERVAL_MS = 2000L
    }

    override fun start() {
        if (isRunning) return
        isRunning = true

        scope.launch {
            try {
                val newSelector = SelectorManager(ioDispatcher)
                selector = newSelector
                val boundSocket = aSocket(newSelector).udp()
                    .bind(InetSocketAddress("0.0.0.0", LISTEN_PORT)) {
                        broadcast = true
                        reuseAddress = true
                    }
                socket = boundSocket
                _state.value = UdpDiscoveryState.Listening
                logger.d(TAG, "UDP Discovery Client started on port $LISTEN_PORT")

                // Periodic eviction so peers that stop broadcasting age out even when
                // no new datagrams arrive to trigger the inline cleanup in handleMessage.
                launch {
                    while (isRunning) {
                        delay(CLEANUP_INTERVAL_MS)
                        evictExpired()
                    }
                }

                while (isRunning) {
                    val datagram = boundSocket.receive()
                    val message = datagram.packet.readByteArray().decodeToString()
                    val fromHost = (datagram.address as? InetSocketAddress)?.hostname ?: ""
                    handleMessage(message, fromHost)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isRunning) {
                    logger.e(TAG, "UDP Discovery Client error", e)
                    _state.value = UdpDiscoveryState.Error(e.message ?: "Unknown error")
                }
            }
        }
    }

    private suspend fun handleMessage(message: String, fromHost: String) {
        logger.d(TAG, "Received message: $message from $fromHost")
        val peer = parseDiscoveryMessage(message) ?: return
        if (peer.port in ignoredServicePorts) {
            logger.d(TAG, "Ignoring local peer announcement on port ${peer.port}")
            return
        }
        logger.d(TAG, "Discovered peer: ${peer.name} at ${peer.host}:${peer.port} (id=${peer.serverDeviceId})")

        peersMutex.withLock {
            peers.upsert(peer, nowMillis())
            _discoveredPeers.value = peers.snapshot()
        }
    }

    private suspend fun evictExpired() {
        peersMutex.withLock {
            if (peers.evictExpired(nowMillis())) {
                _discoveredPeers.value = peers.snapshot()
            }
        }
    }

    override fun stop() {
        isRunning = false
        scope.cancel()
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        try {
            selector?.close()
        } catch (_: Exception) {}
        selector = null
        _state.value = UdpDiscoveryState.Stopped
        // scope was cancelled above, so no coroutine races this clear.
        peers.clear()
        _discoveredPeers.value = emptyList()
        logger.d(TAG, "UDP Discovery Client stopped")
    }
}

/**
 * Pure, non-thread-safe peer table: identity-based dedup + TTL expiry, with
 * timestamps supplied by the caller. Extracted so the dedup/expiry rules are
 * deterministically unit-testable without sockets or wall-clock timing.
 *
 * Dedup key is the [DiscoveredPeer.serverDeviceId] when non-empty (so the same
 * server announcing a new port replaces its old entry), otherwise "host:port"
 * (legacy advertisers that ship no id).
 */
internal class PeerTable(private val ttlMs: Long) {
    private data class Entry(val peer: DiscoveredPeer, val lastSeen: Long)
    private val entries = mutableMapOf<String, Entry>()

    private fun key(peer: DiscoveredPeer): String =
        if (peer.serverDeviceId.isNotEmpty()) "id:${peer.serverDeviceId}" else "${peer.host}:${peer.port}"

    /** Inserts or refreshes [peer] at [now], then drops anything past its TTL. */
    fun upsert(peer: DiscoveredPeer, now: Long) {
        entries[key(peer)] = Entry(peer, now)
        evictExpired(now)
    }

    /** Drops entries last seen before [now] - ttl. Returns true if anything was removed. */
    fun evictExpired(now: Long): Boolean {
        val cutoff = now - ttlMs
        val before = entries.size
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.lastSeen < cutoff) it.remove()
        }
        return entries.size != before
    }

    fun snapshot(): List<DiscoveredPeer> = entries.values.map { it.peer }

    fun clear() = entries.clear()
}

/**
 * Pure parser for a discovery datagram payload. Wire format is
 * `LANCHAT_DISCOVER|name|ip|port|serverDeviceId`. Backward compatible: a 4-field
 * message from an older advertiser yields an empty [DiscoveredPeer.serverDeviceId].
 * Returns null when the message is not a discovery announcement or the port is
 * unparseable. Extracted as a top-level function so the parse + back-compat rules
 * are unit-testable without a live socket.
 */
internal fun parseDiscoveryMessage(message: String): DiscoveredPeer? {
    if (!message.startsWith("LANCHAT_DISCOVER")) return null
    val parts = message.split("|")
    if (parts.size < 4) return null
    val port = parts[3].toIntOrNull() ?: return null
    return DiscoveredPeer(
        name = parts[1],
        host = parts[2],
        port = port,
        serverDeviceId = parts.getOrNull(4) ?: "",
    )
}
