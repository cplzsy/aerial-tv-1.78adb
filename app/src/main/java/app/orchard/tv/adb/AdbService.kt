package app.orchard.tv.adb

import android.content.Context
import android.util.Log
import app.orchard.tv.adb.AdbManager.AdbSessionKind
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The ADB backend: a single entry point the rest of Aerial (or an adb-driven broadcast, see
 * AdbCommandReceiver) calls into. Owns the connection manager, the recent-device store and the two
 * discovery mechanisms, and turns every operation into a result string. There is deliberately no UI:
 * this is the "ADB 管理" capability of skyadb, kept as a headless service.
 */
object AdbService {

    private const val TAG = "Aerial"
    private const val LOG_TAG = "AerialAdb"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var initialized = false
    private lateinit var appContext: Context
    lateinit var manager: AdbManager
        private set
    lateinit var recentDevices: AdbRecentDevices
        private set
    lateinit var mdns: AdbMdnsDiscovery
        private set

    private val scanner = LanAdbScanner()

    /** The most recent result, in plain text, for any consumer that asks. */
    @Volatile var lastResult: String = ""
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            manager = AdbManager()
            recentDevices = AdbRecentDevices(appContext)
            mdns = AdbMdnsDiscovery(appContext)
            initialized = true
        }
    }

    /** Runs [op] with [params] off the calling thread and reports the outcome as a string. */
    fun execute(op: String, params: Map<String, String>, onResult: (String) -> Unit = {}) {
        scope.launch {
            val result = runOp(op, params)
            lastResult = result
            Log.i(LOG_TAG, "op=$op -> $result")
            onResult(result)
        }
    }

    /** Runs [op] on the calling thread (a receiver's background thread) and returns its outcome. */
    fun executeBlocking(op: String, params: Map<String, String>): String {
        val result = runBlocking { runOp(op, params) }
        lastResult = result
        Log.i(LOG_TAG, "op=$op -> $result")
        return result
    }

    /** Whether the backend accepts calls at all (Android 6.0+, where Kadb runs). */
    fun supported(): Boolean = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M

    private suspend fun runOp(op: String, params: Map<String, String>): String {
        return when (op) {
            "connect" -> {
                val host = params["host"] ?: return "error: missing host"
                val port = params["port"]?.toIntOrNull() ?: 5555
                when (val result = manager.connect(host, port)) {
                    is AdbOperationResult.Success -> {
                        recentDevices.defaultPort = port
                        persistRecent(host, port)
                        refreshDeviceName(host, port)
                        "connected $host:$port"
                    }
                    is AdbOperationResult.Failure -> failureText(result)
                }
            }

        "pair" -> {
            val host = params["host"] ?: return "error: missing host"
            val port = params["port"]?.toIntOrNull() ?: return "error: missing port"
            val code = params["code"] ?: return "error: missing code"
            when (val result = manager.pair(host, port, code, params["name"] ?: "aerial")) {
                is AdbOperationResult.Success -> "paired $host:$port"
                is AdbOperationResult.Failure -> failureText(result)
            }
        }

        "disconnect" -> {
            manager.disconnect()
            "disconnected"
        }

        "status" -> {
            val endpoint = manager.currentEndpoint()
            if (endpoint == null) "not connected"
            else "connected $endpoint (${manager.sessionKind()})"
        }

        "deviceinfo" -> {
            when (val result = manager.fetchDeviceInfo()) {
                is AdbOperationResult.Failure -> failureText(result)
                is AdbOperationResult.Success -> {
                    val info = result.data
                    listOf(
                        "brand=${info.brand}",
                        "model=${info.model}",
                        "android=${info.androidVersion}",
                        "sdk=${info.sdk}",
                        "abi=${info.abi}",
                        "resolution=${info.resolution}",
                        "battery=${info.battery}",
                    ).joinToString("\n")
                }
            }
        }

        "shell" -> {
            val command = params["command"] ?: return "error: missing command"
            when (val result = manager.shell(command)) {
                is AdbOperationResult.Failure -> failureText(result)
                is AdbOperationResult.Success -> {
                    val r = result.data
                    buildString {
                        if (r.output.isNotBlank()) append(r.output.trimEnd())
                        if (r.errorOutput.isNotBlank()) {
                            if (isNotEmpty()) append('\n')
                            append("stderr: ").append(r.errorOutput.trimEnd())
                        }
                        append("\nexit=").append(r.exitCode)
                    }
                }
            }
        }

        "install" -> {
            val path = params["file"] ?: return "error: missing file"
            val file = File(path)
            if (!file.isFile) return "error: no such file: $path"
            when (val result = manager.install(file)) {
                is AdbOperationResult.Failure -> failureText(result)
                is AdbOperationResult.Success -> "installed $path"
            }
        }

        "uninstall" -> {
            val pkg = params["pkg"] ?: return "error: missing pkg"
            when (val result = manager.uninstall(pkg)) {
                is AdbOperationResult.Failure -> failureText(result)
                is AdbOperationResult.Success -> "uninstalled $pkg"
            }
        }

        "apps" -> {
            when (val result = manager.listApps()) {
                is AdbOperationResult.Failure -> failureText(result)
                is AdbOperationResult.Success ->
                    result.data.joinToString("\n") { app ->
                        "${app.packageName}|${app.label}|${if (app.isSystem) "system" else "user"}|${if (app.enabled) "enabled" else "disabled"}"
                    }
            }
        }

        "launch" -> {
            val pkg = params["pkg"] ?: return "error: missing pkg"
            when (val result = manager.launchApp(pkg)) {
                is AdbOperationResult.Failure -> failureText(result)
                is AdbOperationResult.Success -> "launched $pkg"
            }
        }

        "forcestop" -> {
            val pkg = params["pkg"] ?: return "error: missing pkg"
            when (val result = manager.forceStopApp(pkg)) {
                is AdbOperationResult.Failure -> failureText(result)
                is AdbOperationResult.Success -> "stopped $pkg"
            }
        }

        "enable", "disable" -> {
            val pkg = params["pkg"] ?: return "error: missing pkg"
            when (val result = manager.setAppEnabled(pkg, enabled = op == "enable")) {
                is AdbOperationResult.Failure -> failureText(result)
                is AdbOperationResult.Success -> "$op $pkg"
            }
        }

        "recent" -> {
            val devices = recentDevices.list()
            if (devices.isEmpty()) "no recent devices"
            else devices.joinToString("\n") { device ->
                "${device.id}|${device.name}|${device.host}|${device.port}|${device.type.name}|${device.lastConnectedText}"
            }
        }

        "scan" -> {
            val host = params["host"]
            val networks =
                if (!host.isNullOrBlank()) {
                    ScanRangeParser.subnetForLocalAddress(host, sourceLabel = "manual")
                        ?.let { listOf(it) }
                        .orEmpty()
                } else {
                    NetworkInfoProvider(appContext).currentLocalNetworks(sourceLabel = "current")
                }
            if (networks.isEmpty()) return "error: no local network to scan"
            val hosts = networks.flatMap { it.expandHosts() }.distinct()
            val found = mutableListOf<AdbScanResult>()
            scanner.scan(
                hosts = hosts,
                ports = listOf(5555),
                onProgress = { p ->
                    Log.i(LOG_TAG, "scanning ${p.scanned}/${p.total}")
                },
                onResult = { result ->
                    found += result
                    Log.i(LOG_TAG, "found ${result.host}:${result.port} ${result.state}")
                },
            )
            if (found.isEmpty()) "scan done: nothing found on ${networks.joinToString { it.subnetLabel }}"
            else found.joinToString("\n") { "${it.host}:${it.port} ${it.state}" }
        }

        "mdns" -> {
            // A bounded one-shot mDNS sweep: start, collect for a moment, stop.
            val endpoints = mutableListOf<AdbMdnsEndpoint>()
            mdns.start()
            try {
                var elapsed = 0L
                while (elapsed < 3_000) {
                    delay(200)
                    elapsed += 200
                    endpoints.clear()
                    endpoints += mdns.state.value.endpoints
                    if (mdns.state.value.error != null) break
                }
            } finally {
                mdns.stop()
            }
            if (endpoints.isEmpty()) "mdns: nothing found"
            else endpoints.joinToString("\n") { "${it.type.name}|${it.name}|${it.host}|${it.port}" }
        }

        "privilege" -> {
            // The ADB privilege pipeline: grants Aerial system-level abilities over the current
            // session (permissions, compile, screensaver, keep-adb, default home, home guard).
            val keepAdb = params["keep_adb"]?.let { it == "true" || it == "1" } ?: true
            when (val result = AdbPrivilege(manager).run(keepAdb)) {
                is AdbOperationResult.Failure -> failureText(result)
                is AdbOperationResult.Success -> serializePrivilegeReport(result.data, keepAdb)
            }
        }

        else -> "error: unknown op $op"
        }
    }

    /** The privilege report as plain text: summary line, then one block per stage. */
    internal fun serializePrivilegeReport(report: AdbPrivilegeReport, keepAdb: Boolean): String =
        buildString {
            appendLine(
                "privilege summary: ${report.succeededCount} ok, ${report.failedCount} failed, " +
                    "${report.skippedCount} skipped (keep_adb=$keepAdb)",
            )
            for (step in report.steps) {
                if (step.skipped) {
                    appendLine("[${step.step.name}] SKIPPED: ${step.skippedReason}")
                } else {
                    appendLine("[${step.step.name}]")
                }
                for (command in step.commands) {
                    val mark = when (command.state) {
                        PrivilegeCommandState.SUCCESS -> "OK  "
                        PrivilegeCommandState.FAILED -> "FAIL"
                        PrivilegeCommandState.SKIPPED -> "SKIP"
                    }
                    appendLine("  $mark ${command.command}")
                    if (command.output.isNotBlank()) {
                        appendLine("       ${command.output.replace("\n", " ").take(300)}")
                    }
                }
            }
        }

    /** After a successful connect: put the device in the recent list with its real name. */
    private suspend fun refreshDeviceName(host: String, port: Int) {
        val info = when (val result = manager.fetchDeviceInfo()) {
            is AdbOperationResult.Success -> result.data
            is AdbOperationResult.Failure -> return
        }
        val entry = AdbDevice(
            id = "$host:$port",
            name = info.displayName,
            host = host,
            port = port,
            type = DeviceType.Unknown,
            connectionState = ConnectionState.Disconnected,
            lastConnectedText = "just connected",
        )
        recentDevices.upsert(entry)
    }

    private fun persistRecent(host: String, port: Int) {
        val entry = AdbDevice(
            id = "$host:$port",
            name = "Android",
            host = host,
            port = port,
            type = DeviceType.Unknown,
            connectionState = ConnectionState.Disconnected,
            lastConnectedText = "just connected",
        )
        recentDevices.upsert(entry)
    }

    private fun failureText(result: AdbOperationResult.Failure): String {
        val detail = result.cause?.message?.take(120)?.let { " ($it)" } ?: ""
        return "error: ${result.message}; ${result.suggestion}$detail"
    }
}
