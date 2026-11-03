package app.orchard.tv.adb

import android.content.Context
import kotlinx.coroutines.delay

/**
 * Connects to this TV's own ADB by direct connect (classic mode): no pairing code. The TV listens
 * on port 5555 (or whatever `_adb._tcp.` announces), and on the first connection the firmware shows
 * the "Allow USB debugging?" dialog on the TV — the user taps Allow / remembers the computer, and
 * the connect call completes. The privilege pipeline then runs over the session.
 */
class AdbLocalConnect(context: Context) {

    private val appContext = context.applicationContext
    private val mdns = AdbMdnsDiscovery(appContext)
    private val networkInfo = NetworkInfoProvider(appContext)

    /** This TV's own IPv4 addresses; the connect target is the first one. */
    private val localHosts: List<String>
        get() = networkInfo.currentLocalNetworks(sourceLabel = "local").map { it.deviceIp }

    /**
     * Direct-connects to this TV: prefer the classic ADB service port announced via mDNS
     * (`_adb._tcp.`), fall back to 5555, then connect. While the call is in flight the TV may show
     * the allow dialog; the longer connect timeout leaves the user time to tap it.
     */
    suspend fun connectLocal(): AdbOperationResult<String> {
        val ip = localHosts.firstOrNull()
            ?: return AdbOperationResult.Failure(
                message = "no local IPv4 address",
                suggestion = "make sure the TV is on a network with an address",
            )
        val announcedPort = discover(AdbMdnsServiceType.Legacy)?.port
        val port = announcedPort ?: 5555
        return AdbService.manager.connect(
            host = ip,
            port = port,
            connectTimeoutMillis = 20_000,
        )
    }

    /** One short mDNS sweep for [type], preferring this TV's own address. */
    private suspend fun discover(type: AdbMdnsServiceType): AdbMdnsEndpoint? {
        mdns.start(setOf(type))
        try {
            val deadline = System.currentTimeMillis() + ScanMillis
            while (System.currentTimeMillis() < deadline) {
                delay(PollMillis)
                val endpoints = mdns.state.value.endpoints
                val own = endpoints.firstOrNull { it.type == type && it.host in localHosts }
                if (own != null) return own
            }
            // No entry matched this TV's address; still offer the first of the type.
            return mdns.state.value.endpoints.firstOrNull { it.type == type }
        } finally {
            mdns.stop()
        }
    }

    private companion object {
        const val ScanMillis = 2_500L
        const val PollMillis = 200L
    }
}
