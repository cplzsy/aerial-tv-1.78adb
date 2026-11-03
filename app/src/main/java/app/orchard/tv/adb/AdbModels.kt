package app.orchard.tv.adb

import kotlinx.coroutines.CancellationException

/**
 * The outcome of an ADB operation. Cancellation is lifecycle control and never becomes a
 * [AdbOperationResult.Failure]: [operationCatching] rethrows [CancellationException] so the caller's
 * coroutine keeps its cancellation semantics.
 */
sealed interface AdbOperationResult<out T> {
    data class Success<T>(val data: T) : AdbOperationResult<T>
    data class Failure(
        val message: String,
        val suggestion: String,
        val cause: Throwable? = null,
        val output: String? = null,
    ) : AdbOperationResult<Nothing>
}

/** The parsed result of one `adb shell` command. */
data class ShellCommandResult(
    val command: String,
    val output: String,
    val errorOutput: String,
    val exitCode: Int,
)

/** What a `pm list packages` run knows about one app on the remote device. */
data class AppInfo(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val enabled: Boolean = true,
)

/** Properties fetched from the remote device's getprop / wm / dumpsys. */
data class DeviceInfo(
    val brand: String,
    val model: String,
    val androidVersion: String,
    val sdk: String,
    val abi: String,
    val resolution: String,
    val battery: String,
) {
    val displayName: String
        get() = listOf(brand, model).filter { it.isNotBlank() && it != UNKNOWN }.joinToString(" ").ifBlank { "Android" }

    companion object {
        const val UNKNOWN = "Unknown"
        val Empty = DeviceInfo(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN)
    }
}

/** How a known device is reached over the network. */
enum class AdbLinkKind(val label: String) {
    Wifi("Wi-Fi"),
}

/** The rough kind of a discovered device, inferred from its properties. */
enum class DeviceType {
    Phone, Tablet, Tv, Box, Unknown,
}

/** The connection state of a device in the recent list. */
enum class ConnectionState {
    Disconnected, Connecting, Connected, Failed, Offline,
}

/** One entry of the recently connected devices, kept in a local preferences file. */
data class AdbDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val type: DeviceType,
    val connectionState: ConnectionState,
    val lastConnectedText: String,
    val linkKind: AdbLinkKind = AdbLinkKind.Wifi,
)

/** Cancellation is lifecycle control, never an operation failure. */
inline fun <T> operationCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
