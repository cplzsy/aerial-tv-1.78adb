package app.orchard.tv.adb

import com.flyfishxu.kadb.Kadb
import java.io.File
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.source

/**
 * The ADB session manager, ported from skyadb's KadbManager and trimmed to what Aerial needs:
 * one active WiFi ADB session on a single host. It owns the [Kadb] client (created and closed
 * behind a mutex), probes the connection with `echo kadb_ready` before a session counts as
 * established, and wraps every operation in [operationCatching] so a failure carries a readable
 * message and suggestion instead of an exception.
 *
 * The Kadb library is minSdk 23; this class is only ever used on Android 6.0+ (the command
 * receiver refuses calls on older systems), so its calls never run on Android 5.x.
 */
class AdbManager {

    private val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()
    @Volatile private var activeKadb: Kadb? = null
    @Volatile private var activeEndpoint: String? = null
    @Volatile private var sessionKind: AdbSessionKind = AdbSessionKind.None

    enum class AdbSessionKind { None, Wifi }

    /**
     * Connects to a WiFi ADB endpoint. A connection only becomes the active session after the probe
     * (`echo kadb_ready`) succeeds; anything earlier closes the client again, so a wrong port or an
     * unpaired endpoint never leaves a half-open session behind.
     */
    suspend fun connect(
        host: String,
        port: Int,
        connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
        socketTimeoutMillis: Int = DEFAULT_SOCKET_TIMEOUT_MILLIS,
    ): AdbOperationResult<String> =
        withContext(Dispatchers.IO) {
            sessionMutex.withLock {
                disconnectLocked()
                val endpoint = "$host:$port"
                operationCatching {
                    val kadb = Kadb.create(host, port, connectTimeoutMillis, socketTimeoutMillis)
                    try {
                        probeConnection(kadb, maxOf(connectTimeoutMillis, socketTimeoutMillis))
                        activeKadb = kadb
                        activeEndpoint = endpoint
                        sessionKind = AdbSessionKind.Wifi
                        AdbOperationResult.Success(endpoint)
                    } catch (error: Throwable) {
                        kadb.close()
                        throw error
                    }
                }.getOrElse { error ->
                    AdbOperationResult.Failure(
                        message = "Cannot connect to $endpoint",
                        suggestion = "Check that the device has wireless debugging or ADB over Wi-Fi on, and that it accepts this key.",
                        cause = error,
                    )
                }
            }
        }

    /** Pairs with an Android 11+ device in wireless-debugging pairing mode. */
    suspend fun pair(
        host: String,
        port: Int,
        pairingCode: String,
        name: String = "aerial",
    ): AdbOperationResult<Unit> =
        withContext(Dispatchers.IO) {
            operationCatching {
                Kadb.pair(host, port, pairingCode, name)
                AdbOperationResult.Success(Unit)
            }.getOrElse { error ->
                AdbOperationResult.Failure(
                    message = "Pairing with $host:$port failed",
                    suggestion = "Make sure the pairing code is current and the device is still showing the pairing dialog.",
                    cause = error,
                )
            }
        }

    suspend fun shell(command: String): AdbOperationResult<ShellCommandResult> =
        withContext(Dispatchers.IO) {
            val kadb = activeKadb
                ?: return@withContext notConnected("Run connect first.")
            operationCatching {
                val response = kadb.shell(command)
                AdbOperationResult.Success(
                    ShellCommandResult(
                        command = command,
                        output = response.output,
                        errorOutput = response.errorOutput,
                        exitCode = response.exitCode,
                    )
                )
            }.getOrElse { error ->
                AdbOperationResult.Failure(
                    message = "Shell command failed",
                    suggestion = "The session may have dropped; reconnect and try again.",
                    cause = error,
                )
            }
        }

    suspend fun fetchDeviceInfo(): AdbOperationResult<DeviceInfo> =
        withContext(Dispatchers.IO) {
            val kadb = activeKadb
                ?: return@withContext notConnected("Run connect first.")
            operationCatching {
                fun prop(name: String) = kadb.shell("getprop $name").output.trim().ifBlank { DeviceInfo.UNKNOWN }
                AdbOperationResult.Success(
                    DeviceInfo(
                        brand = prop("ro.product.brand"),
                        model = prop("ro.product.model"),
                        androidVersion = prop("ro.build.version.release"),
                        sdk = prop("ro.build.version.sdk"),
                        abi = prop("ro.product.cpu.abi"),
                        resolution = parseResolution(kadb.shell("wm size").output),
                        battery = parseBatteryLevel(kadb.shell("dumpsys battery").output),
                    )
                )
            }.getOrElse { error ->
                AdbOperationResult.Failure(
                    message = "Reading device info failed",
                    suggestion = "The session may have dropped; reconnect and try again.",
                    cause = error,
                )
            }
        }

    /** Installs an APK from the local filesystem onto the connected device. */
    suspend fun install(
        apkFile: File,
        onProgress: ((transferred: Long, total: Long) -> Unit)? = null,
    ): AdbOperationResult<Unit> =
        withContext(Dispatchers.IO) {
            val kadb = activeKadb
                ?: return@withContext notConnected("Run connect first.")
            operationCatching {
                val total = apkFile.length()
                apkFile.source().use { raw ->
                    val source = if (onProgress == null) raw else CountingSource(raw, total, onProgress)
                    kadb.install(source, total)
                }
                AdbOperationResult.Success(Unit)
            }.getOrElse { error ->
                AdbOperationResult.Failure(
                    message = "Installing ${apkFile.name} failed",
                    suggestion = "The device may reject the APK (signature, sdk or architecture). Check its logcat for the install error.",
                    cause = error,
                )
            }
        }

    /**
     * Pushes a local file to [remotePath] on the connected device, e.g. `/data/local/tmp`.
     * Used before replacing this app's package: an uninstall deletes the app's own files, so the
     * APK must live somewhere the uninstall leaves alone.
     */
    suspend fun push(
        localFile: File,
        remotePath: String,
        onProgress: ((transferred: Long, total: Long) -> Unit)? = null,
    ): AdbOperationResult<Unit> =
        withContext(Dispatchers.IO) {
            val kadb = activeKadb
                ?: return@withContext notConnected("Run connect first.")
            operationCatching {
                val total = localFile.length()
                localFile.source().use { raw ->
                    val source = if (onProgress == null) raw else CountingSource(raw, total, onProgress)
                    kadb.push(source, remotePath, PUSH_MODE, localFile.lastModified())
                }
                AdbOperationResult.Success(Unit)
            }.getOrElse { error ->
                AdbOperationResult.Failure(
                    message = "Pushing ${localFile.name} failed",
                    suggestion = "The session may have dropped; reconnect and try again.",
                    cause = error,
                )
            }
        }

    suspend fun uninstall(packageName: String): AdbOperationResult<Unit> =
        withContext(Dispatchers.IO) {
            val kadb = activeKadb
                ?: return@withContext notConnected("Run connect first.")
            operationCatching {
                kadb.uninstall(packageName)
                AdbOperationResult.Success(Unit)
            }.getOrElse { error ->
                AdbOperationResult.Failure(
                    message = "Uninstalling $packageName failed",
                    suggestion = "The package may be a system app that cannot be uninstalled.",
                    cause = error,
                )
            }
        }

    suspend fun forceStopApp(packageName: String): AdbOperationResult<Unit> =
        withContext(Dispatchers.IO) {
            when (val result = shell("am force-stop ${shellQuote(packageName)}")) {
                is AdbOperationResult.Failure -> result
                is AdbOperationResult.Success -> {
                    if (result.data.exitCode == 0) AdbOperationResult.Success(Unit)
                    else AdbOperationResult.Failure(
                        message = "Stopping $packageName failed",
                        suggestion = result.data.errorOutput.ifBlank { "Is the app running?" },
                    )
                }
            }
        }

    suspend fun launchApp(packageName: String): AdbOperationResult<Unit> =
        withContext(Dispatchers.IO) {
            when (val result = shell("monkey -p ${shellQuote(packageName)} 1")) {
                is AdbOperationResult.Failure -> result
                is AdbOperationResult.Success -> {
                    if (result.data.exitCode == 0) AdbOperationResult.Success(Unit)
                    else AdbOperationResult.Failure(
                        message = "Launching $packageName failed",
                        suggestion = result.data.errorOutput.ifBlank { "Is the app installed and enabled?" },
                    )
                }
            }
        }

    suspend fun setAppEnabled(packageName: String, enabled: Boolean): AdbOperationResult<Unit> =
        withContext(Dispatchers.IO) {
            val command = if (enabled) "pm enable ${shellQuote(packageName)}"
            else "pm disable-user --user 0 ${shellQuote(packageName)}"
            when (val result = shell(command)) {
                is AdbOperationResult.Failure -> result
                is AdbOperationResult.Success -> {
                    if (result.data.exitCode == 0) AdbOperationResult.Success(Unit)
                    else AdbOperationResult.Failure(
                        message = if (enabled) "Enabling $packageName failed" else "Disabling $packageName failed",
                        suggestion = result.data.errorOutput.ifBlank { "The package may not allow this." },
                    )
                }
            }
        }

    /** Lists third-party and system apps; `enabled` comes from `pm list packages -d`. */
    suspend fun listApps(): AdbOperationResult<List<AppInfo>> =
        withContext(Dispatchers.IO) {
            val kadb = activeKadb
                ?: return@withContext notConnected("Run connect first.")
            operationCatching {
                val disabled = parsePackageNames(kadb.shell("pm list packages -d").output).toSet()
                val user = parsePackageList(kadb.shell("pm list packages -3").output, isSystem = false)
                val system = parsePackageList(kadb.shell("pm list packages -s").output, isSystem = true)
                AdbOperationResult.Success(
                    (user + system)
                        .map { app -> app.copy(enabled = app.packageName !in disabled) }
                        .sortedWith(compareBy<AppInfo> { !it.enabled }.thenBy { it.packageName })
                )
            }.getOrElse { error ->
                AdbOperationResult.Failure(
                    message = "Listing apps failed",
                    suggestion = "The session may have dropped; reconnect and try again.",
                    cause = error,
                )
            }
        }

    suspend fun disconnect() =
        withContext(Dispatchers.IO) {
            sessionMutex.withLock { disconnectLocked() }
        }

    private fun disconnectLocked() {
        activeKadb?.close()
        activeKadb = null
        activeEndpoint = null
        sessionKind = AdbSessionKind.None
    }

    /** Closing Kadb aborts its pending socket; cancelling a Job alone cannot. */
    private suspend fun probeConnection(client: Kadb, timeoutMillis: Int) {
        try {
            withTimeout(timeoutMillis.toLong()) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation { client.close() }
                    connectionScope.launch {
                        val result = operationCatching {
                            check(client.shell("echo kadb_ready").exitCode == 0) { "ADB probe failed" }
                        }
                        if (continuation.isActive) continuation.resumeWith(result)
                        else client.close()
                    }
                }
            }
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw SocketTimeoutException("ADB authorization timed out after ${timeoutMillis}ms")
                .apply { initCause(error) }
        }
    }

    fun sessionKind(): AdbSessionKind = sessionKind

    fun currentEndpoint(): String? = activeEndpoint

    private fun notConnected(suggestion: String): AdbOperationResult.Failure =
        AdbOperationResult.Failure(
            message = "Not connected",
            suggestion = suggestion,
        )

    private fun parseResolution(output: String): String =
        output.lineSequence()
            .firstOrNull { it.contains("Physical size") || it.contains("Override size") }
            ?.substringAfter(":")
            ?.trim()
            ?.ifBlank { null }
            ?: DeviceInfo.UNKNOWN

    private fun parseBatteryLevel(output: String): String {
        val level = output.lineSequence()
            .firstOrNull { it.trim().startsWith("level:") }
            ?.substringAfter(":")
            ?.trim()
        return if (level.isNullOrBlank()) DeviceInfo.UNKNOWN else "$level%"
    }

    private fun parsePackageList(output: String, isSystem: Boolean): List<AppInfo> =
        parsePackageNames(output).map { packageName ->
            AppInfo(
                packageName = packageName,
                label = fallbackLabel(packageName),
                isSystem = isSystem,
            )
        }

    private fun parsePackageNames(output: String): List<String> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .filter { it.isNotBlank() }
            .toList()

    /** A readable name for an app whose label the remote device did not hand us. */
    private fun fallbackLabel(packageName: String): String {
        val last = packageName.substringAfterLast('.')
        val label = last.ifBlank { packageName }
        return label.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000
        const val DEFAULT_SOCKET_TIMEOUT_MILLIS = 30_000
        /** Remote file mode for pushed files, 0644. */
        private const val PUSH_MODE = 420
    }
}
