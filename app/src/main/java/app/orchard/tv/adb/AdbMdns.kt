package app.orchard.tv.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * mDNS discovery of ADB devices, replacing skyadb's kadb-mdns dependency with the system
 * [NsdManager] (the kadb-mdns artifacts are compiled for Kotlin 2.4 and cannot be consumed by
 * Aerial's Kotlin 2.2). Discovers the same three service types skyadb listens for.
 */
enum class AdbMdnsServiceType(val nsdType: String) {
    Pairing("_adb-tls-pairing._tcp."),
    Connect("_adb-tls-connect._tcp."),
    Legacy("_adb._tcp."),
}

data class AdbMdnsEndpoint(
    val name: String,
    val host: String,
    val port: Int,
    val type: AdbMdnsServiceType,
) {
    val id: String = "${type.name}:$host:$port"
    val endpoint: String = "$host:$port"
}

data class AdbMdnsDiscoveryState(
    val running: Boolean = false,
    val endpoints: List<AdbMdnsEndpoint> = emptyList(),
    val error: String? = null,
)

class AdbMdnsDiscovery(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val mutableState = MutableStateFlow(AdbMdnsDiscoveryState())
    val state = mutableState.asStateFlow()

    private var nsdManager: NsdManager? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListeners = mutableMapOf<String, NsdManager.ResolveListener>()
    private var job: Job? = null

    @Synchronized
    fun start(types: Set<AdbMdnsServiceType> = AdbMdnsServiceType.entries.toSet()) {
        if (nsdManager != null || types.isEmpty()) return
        val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = nsd
        multicastLock = (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("aerial-adb-mdns")
            ?.apply { setReferenceCounted(false); acquire() }

        mutableState.value = AdbMdnsDiscoveryState(running = true)

        val seen = mutableSetOf<String>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val type = types.firstOrNull { it.nsdType == serviceInfo.serviceType } ?: return
                val key = "${type.name}:${serviceInfo.serviceName}"
                if (!seen.add(key)) return
                val resolveListener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        scope.launch { seen.remove(key) }
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        val endpoint = AdbMdnsEndpoint(
                            name = info.serviceName,
                            host = host,
                            port = info.port,
                            type = type,
                        )
                        scope.launch {
                            mutableState.value = mutableState.value.copy(
                                endpoints = (mutableState.value.endpoints + endpoint)
                                    .distinctBy { it.id },
                            )
                        }
                    }
                }
                resolveListeners[key] = resolveListener
                nsd.resolveService(serviceInfo, resolveListener)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val key = "${serviceInfo.serviceType}:${serviceInfo.serviceName}"
                scope.launch {
                    mutableState.value = mutableState.value.copy(
                        endpoints = mutableState.value.endpoints.filterNot { endpoint ->
                            endpoint.type.nsdType == serviceInfo.serviceType && endpoint.name == serviceInfo.serviceName
                        },
                    )
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                scope.launch {
                    mutableState.value = AdbMdnsDiscoveryState(running = false, error = "start failed ($errorCode)")
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        job = scope.launch {
            for (type in types) {
                runCatching { nsd.discoverServices(type.nsdType, NsdManager.PROTOCOL_DNS_SD, listener) }
                    .onFailure { error ->
                        mutableState.value = AdbMdnsDiscoveryState(running = false, error = error.message)
                    }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        nsdManager?.let { nsd ->
            discoveryListener?.let { listener -> runCatching { nsd.stopServiceDiscovery(listener) } }
            resolveListeners.keys.toList().forEach { key ->
                // Resolution cannot be cancelled through NsdManager; dropping the listener is enough.
                resolveListeners.remove(key)
            }
        }
        discoveryListener = null
        nsdManager = null
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
        mutableState.value = AdbMdnsDiscoveryState()
    }
}
