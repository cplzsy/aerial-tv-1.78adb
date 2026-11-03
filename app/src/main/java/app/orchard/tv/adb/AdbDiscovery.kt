package app.orchard.tv.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import java.io.EOFException
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

// ---- Models ------------------------------------------------------------------------------------

/** A subnet to scan: the network the TV is on, or one configured by hand. */
data class LocalNetwork(
    val deviceIp: String,
    val subnetLabel: String,
    val sourceLabel: String,
    val hostCount: Int,
    private val networkInt: Int,
    private val broadcastInt: Int,
    private val prefixLength: Int,
    private val excludedHost: String?,
) {
    fun expandHosts(): List<String> {
        val hosts = when (prefixLength) {
            32 -> listOf(deviceIp)
            31 -> listOf(networkInt.toIpv4String(), broadcastInt.toIpv4String())
            else -> ((networkInt + 1)..(broadcastInt - 1)).map { it.toIpv4String() }
        }
        return if (excludedHost == null) hosts else hosts.filterNot { it == excludedHost }
    }
}

internal fun Int.toIpv4String(): String =
    listOf(
        this ushr 24 and 0xff,
        this ushr 16 and 0xff,
        this ushr 8 and 0xff,
        this and 0xff,
    ).joinToString(".")

/** One probed host:port pair and what the ADB handshake said about it. */
data class AdbScanResult(
    val host: String,
    val port: Int,
    val state: AdbProbeState,
    val latencyMs: Long,
) {
    val endpoint: String = "$host:$port"
}

enum class AdbProbeState(val visible: Boolean) {
    /** Port closed or host unreachable; shown only while the scan runs, never in the result list. */
    PortClosed(false),
    /** The port is open but does not speak ADB. */
    NotAdb(false),
    /** A TCP port answered, but not with an ADB packet. */
    PortOpen(true),
    /** ADB answered A_AUTH: a device with wired debugging, waiting for the key to be accepted. */
    AdbUnauthorized(true),
    /** ADB answered A_CNXN: authorized, connectable. */
    AdbAvailable(true),
    /** ADB answered A_STLS: Android 11+ wireless debugging, needs pairing first. */
    AdbSecure(true),
    /** The probe itself failed (network error). */
    Failed(false),
}

data class AdbScanProgress(
    val scanned: Int,
    val total: Int,
)

// ---- Range parsing ------------------------------------------------------------------------------

/** Parses CIDR-style ranges and derives the current subnet from a local address. */
object ScanRangeParser {

    fun subnetForLocalAddress(address: String, sourceLabel: String): LocalNetwork? {
        if (!address.isPrivateIpv4()) return null
        return createSubnet(address = address, prefixLength = DefaultPrefixLength, sourceLabel = sourceLabel)
    }

    private fun createSubnet(
        address: String,
        prefixLength: Int,
        sourceLabel: String,
        excludedHost: String? = address,
    ): LocalNetwork? {
        val addressInt = address.toIpv4IntOrNull() ?: return null
        val mask = prefixLength.toMask()
        val network = addressInt and mask
        val broadcast = network or mask.inv()
        val rawCount = when (prefixLength) {
            32 -> 1
            31 -> 2
            else -> (broadcast - network - 1).coerceAtLeast(0)
        }
        val excludedInRange = excludedHost != null && when (prefixLength) {
            32 -> excludedHost == address
            31 -> {
                val excludedInt = excludedHost.toIpv4IntOrNull()
                excludedInt == network || excludedInt == broadcast
            }
            else -> {
                val excludedInt = excludedHost.toIpv4IntOrNull()
                excludedInt != null && excludedInt in (network + 1) until broadcast
            }
        }
        val hostCount = if (excludedInRange) (rawCount - 1).coerceAtLeast(0) else rawCount
        return LocalNetwork(
            deviceIp = address,
            subnetLabel = "${network.toIpv4String()}/$prefixLength",
            sourceLabel = sourceLabel,
            hostCount = hostCount,
            networkInt = network,
            broadcastInt = broadcast,
            prefixLength = prefixLength,
            excludedHost = excludedHost,
        )
    }

    private fun String.isPrivateIpv4(): Boolean {
        val parts = split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 ||
            parts[0] == 192 && parts[1] == 168 ||
            parts[0] == 172 && parts[1] in 16..31
    }

    private fun String.toIpv4IntOrNull(): Int? {
        val parts = split('.')
        if (parts.size != 4) return null
        var value = 0
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet
        }
        return value
    }

    private fun Int.toMask(): Int = if (this == 0) 0 else -1 shl (32 - this)

    private const val DefaultPrefixLength = 24
}

// ---- Where the current subnet comes from ---------------------------------------------------------

/** The local IPv4 networks to scan, from the active connection, falling back to interface enumeration. */
class NetworkInfoProvider(context: Context) {

    private val connectivityManager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun currentLocalNetworks(sourceLabel: String): List<LocalNetwork> {
        val active = activeLinkRanges(sourceLabel)
        if (active.isNotEmpty()) return active
        return networkInterfaceAddress(sourceLabel)
            ?.let { listOf(it) }
            .orEmpty()
    }

    private fun activeLinkRanges(sourceLabel: String): List<LocalNetwork> {
        val cm = connectivityManager ?: return emptyList()
        // ConnectivityManager.activeNetwork is Android 6+; this whole feature runs only there.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return emptyList()
        val network = cm.activeNetwork ?: return emptyList()
        val linkProperties = cm.getLinkProperties(network) ?: return emptyList()
        return linkProperties.linkAddresses
            .asSequence()
            .filter { it.address is Inet4Address }
            .mapNotNull { linkAddress -> linkAddress.toLocalNetwork(sourceLabel) }
            .filter { it.hostCount > 0 }
            .distinctBy { it.subnetLabel }
            .toList()
    }

    private fun LinkAddress.toLocalNetwork(sourceLabel: String): LocalNetwork? =
        ScanRangeParser.subnetForLocalAddress(
            address = address.hostAddress.orEmpty(),
            sourceLabel = sourceLabel,
        )

    private fun networkInterfaceAddress(sourceLabel: String): LocalNetwork? =
        runCatching {
            NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { networkInterface -> networkInterface.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress.orEmpty() }
                .firstNotNullOfOrNull { address ->
                    ScanRangeParser.subnetForLocalAddress(address, sourceLabel = sourceLabel)
                }
        }.getOrNull()
}

// ---- The port scan --------------------------------------------------------------------------------

/** Scans host×port targets with a bounded number of concurrent probes. */
class LanAdbScanner(
    private val probe: AdbProtocolProbe = AdbProtocolProbe(),
) {
    suspend fun scan(
        hosts: List<String>,
        ports: List<Int>,
        concurrency: Int = DefaultConcurrency,
        timeoutMillis: Int = DefaultTimeoutMillis,
        onProgress: suspend (AdbScanProgress) -> Unit,
        onResult: suspend (AdbScanResult) -> Unit,
    ) = coroutineScope {
        val targets = hosts.flatMap { host -> ports.distinct().map { port -> host to port } }
        val total = targets.size
        val scanned = AtomicInteger(0)
        val lastProgressAt = AtomicLong(0L)
        val semaphore = Semaphore(concurrency.coerceIn(1, MaxConcurrency))

        onProgress(AdbScanProgress(scanned = 0, total = total))

        targets.map { (host, port) ->
            async {
                ensureActive()
                semaphore.withPermit {
                    ensureActive()
                    val result = probe.probe(host = host, port = port, timeoutMillis = timeoutMillis)
                    val current = scanned.incrementAndGet()
                    val now = System.currentTimeMillis()
                    val previous = lastProgressAt.get()
                    if (
                        current == total ||
                        current % ProgressUpdateStep == 0 ||
                        now - previous >= ProgressUpdateIntervalMillis && lastProgressAt.compareAndSet(previous, now)
                    ) {
                        onProgress(AdbScanProgress(scanned = current, total = total))
                    }
                    if (result.state.visible) {
                        onResult(result)
                    }
                }
            }
        }.awaitAll()
    }

    private companion object {
        const val DefaultConcurrency = 48
        const val MaxConcurrency = 64
        const val DefaultTimeoutMillis = 600
        const val ProgressUpdateStep = 8
        const val ProgressUpdateIntervalMillis = 120L
    }
}

/**
 * Opens a socket to a host:port and starts the ADB handshake (`host::...` connect packet), then
 * classifies the answer: A_CNXN means an authorized ADB, A_AUTH an unauthorized one, A_STLS a
 * wireless-debugging endpoint that needs pairing, anything else is not ADB.
 */
class AdbProtocolProbe {

    suspend fun probe(host: String, port: Int, timeoutMillis: Int): AdbScanResult = withContext(Dispatchers.IO) {
        var state = AdbProbeState.Failed
        val latency = measureTimeMillis {
            state = probeState(host = host, port = port, timeoutMillis = timeoutMillis)
        }
        AdbScanResult(host = host, port = port, state = state, latencyMs = latency)
    }

    private fun probeState(host: String, port: Int, timeoutMillis: Int): AdbProbeState {
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
            socket.soTimeout = timeoutMillis

            val payload = "host::aerial\u0000".toByteArray(Charsets.UTF_8)
            socket.getOutputStream().write(createPacket(command = A_CNXN, arg0 = A_VERSION, arg1 = MaxPayload, payload))
            socket.getOutputStream().flush()

            val header = readExactly(socket, HeaderLength)
            val command = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
            when (command) {
                A_CNXN -> AdbProbeState.AdbAvailable
                A_AUTH -> AdbProbeState.AdbUnauthorized
                A_STLS -> AdbProbeState.AdbSecure
                else -> AdbProbeState.NotAdb
            }
        } catch (_: SocketTimeoutException) {
            if (socket.isConnected) AdbProbeState.PortOpen else AdbProbeState.PortClosed
        } catch (_: EOFException) {
            if (socket.isConnected) AdbProbeState.PortOpen else AdbProbeState.PortClosed
        } catch (_: IOException) {
            if (socket.isConnected) AdbProbeState.PortOpen else AdbProbeState.PortClosed
        } catch (_: Throwable) {
            AdbProbeState.Failed
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun createPacket(command: Int, arg0: Int, arg1: Int, payload: ByteArray): ByteArray {
        val checksum = payload.sumOf { it.toInt() and 0xff }
        return ByteBuffer
            .allocate(HeaderLength + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(payload.size)
            .putInt(checksum)
            .putInt(command xor -1)
            .put(payload)
            .array()
    }

    private fun readExactly(socket: Socket, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        val input = socket.getInputStream()
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read == -1) throw EOFException()
            offset += read
        }
        return buffer
    }

    private companion object {
        const val HeaderLength = 24
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_STLS = 0x534c5453
        const val A_VERSION = 0x01000000
        const val MaxPayload = 4096
    }
}
