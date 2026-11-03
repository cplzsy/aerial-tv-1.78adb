package app.orchard.tv.adb

import android.content.Context
import android.content.SharedPreferences

/**
 * The recently connected WiFi ADB devices, kept in Aerial's own preferences file (no DataStore
 * dependency: the app already uses plain SharedPreferences everywhere). Ported from skyadb's
 * DataStore-backed RecentDeviceStore, same pipe-separated encoding and escaping.
 */
class AdbRecentDevices(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Most recent first, at most [MaxRecentDevices]. */
    fun list(): List<AdbDevice> =
        prefs.getString(KEY_DEVICES, null)
            .orEmpty()
            .lineSequence()
            .mapNotNull(::decodeDevice)
            .filter { it.linkKind == AdbLinkKind.Wifi }
            .toList()

    /** Puts [device] on top, dropping an older entry with the same id. */
    fun upsert(device: AdbDevice) {
        val next = listOf(device) + list().filterNot { it.id == device.id }
        prefs.edit()
            .putString(KEY_DEVICES, next.take(MaxRecentDevices).joinToString(separator = "\n", transform = ::encodeDevice))
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_DEVICES).apply()
    }

    /** The port manual connects default to, remembered from the last successful manual connect. */
    var defaultPort: Int
        get() = prefs.getInt(KEY_DEFAULT_PORT, 5555)
        set(value) {
            prefs.edit().putInt(KEY_DEFAULT_PORT, value).apply()
        }

    private fun encodeDevice(device: AdbDevice): String =
        listOf(
            escape(device.id),
            escape(device.name),
            escape(device.host),
            device.port.toString(),
            device.type.name,
            device.connectionState.name,
            escape(device.lastConnectedText),
            device.linkKind.name,
        ).joinToString("|")

    private fun decodeDevice(value: String): AdbDevice? {
        val parts = value.split("|")
        if (parts.size !in 7..8) return null
        val port = parts[3].toIntOrNull() ?: return null
        return AdbDevice(
            id = unescape(parts[0]),
            name = unescape(parts[1]),
            host = unescape(parts[2]),
            port = port,
            type = DeviceType.entries.firstOrNull { it.name == parts[4] } ?: DeviceType.Unknown,
            connectionState = ConnectionState.Disconnected,
            lastConnectedText = unescape(parts[6]),
            linkKind = parts.getOrNull(7)?.let { name ->
                AdbLinkKind.entries.firstOrNull { it.name == name }
            } ?: AdbLinkKind.Wifi,
        )
    }

    private fun escape(value: String): String =
        value.replace("%", "%25").replace("|", "%7C").replace("\n", "%0A")

    private fun unescape(value: String): String =
        value.replace("%0A", "\n").replace("%7C", "|").replace("%25", "%")

    companion object {
        private const val PREFS_NAME = "adb"
        private const val KEY_DEVICES = "recent_devices"
        private const val KEY_DEFAULT_PORT = "default_port"
        private const val MaxRecentDevices = 8
    }
}
