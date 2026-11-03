package app.orchard.tv.adb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One downloadable APK from the latest short-drama hub release. */
data class ApkAsset(
    val name: String,
    val url: String,
    val size: Long,
) {
    /** True when the file name carries the arm64-v8a ABI marker (modern TVs). */
    val isArm64: Boolean get() = name.contains("arm64-v8a", ignoreCase = true)

    /** True when the file name carries the armeabi-v7a ABI marker (32-bit TVs). */
    val isArmV7: Boolean get() = name.contains("armeabi-v7a", ignoreCase = true)
}

/**
 * Backs the "short-drama hub upgrade" action: reads the latest release manifest from GitHub and
 * downloads its APK assets into the app's files. Download and install run over the ADB session
 * established by the caller; this object only handles the network half.
 */
object AdbDramaUpgrade {

    private const val TAG = "AerialAdb"
    private const val SHORT_DRAMA_REPO = "jackherex/duanjuzhijia-down-tv"
    private const val HGTV_REPO = "pan8664716/HGTV"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** The APK assets of the latest short-drama hub release, in manifest order. */
    suspend fun fetchLatestAssets(): AdbOperationResult<List<ApkAsset>> =
        fetchAssets(SHORT_DRAMA_REPO, "short-drama hub")

    /** The APK assets of the latest HGTV release, in manifest order. */
    suspend fun fetchHgAssets(): AdbOperationResult<List<ApkAsset>> =
        fetchAssets(HGTV_REPO, "HGTV")

    private suspend fun fetchAssets(
        repo: String,
        label: String,
    ): AdbOperationResult<List<ApkAsset>> =
        withContext(Dispatchers.IO) {
            operationCatching {
                val c =
                    URL("https://api.github.com/repos/$repo/releases/latest")
                        .openConnection() as HttpURLConnection
                val apks: List<ApkAsset> = try {
                    c.requestMethod = "GET"
                    // GitHub's API rejects requests without a user agent.
                    c.setRequestProperty("User-Agent", "Aerial/1.77")
                    c.setRequestProperty("Accept", "application/vnd.github+json")
                    c.connectTimeout = CONNECT_TIMEOUT_MS
                    c.readTimeout = READ_TIMEOUT_MS
                    if (c.responseCode != HttpURLConnection.HTTP_OK) {
                        error("HTTP ${c.responseCode} from GitHub")
                    }
                    val text = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val assets = JSONObject(text).optJSONArray("assets") ?: JSONArray()
                    buildList {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name")
                            val url = asset.optString("browser_download_url")
                            if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                                add(ApkAsset(name, url, asset.optLong("size", 0L)))
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                }
                AdbOperationResult.Success(apks)
            }.getOrElse { error ->
                AdbOperationResult.Failure(
                    message = "Could not fetch the latest $label release",
                    suggestion = "Check the TV's network, then try again.",
                    cause = error,
                )
            }
        }

    /**
     * Downloads [asset] into `<files>/adb-upgrades`, reporting progress. The callback runs on a
     * background thread, so callers must hop back to the main thread before touching UI state.
     */
    suspend fun downloadApk(
        appContext: Context,
        asset: ApkAsset,
        onProgress: ((done: Long, total: Long?) -> Unit)? = null,
    ): AdbOperationResult<File> =
        withContext(Dispatchers.IO) {
            val dir = File(appContext.filesDir, "adb-upgrades").apply { mkdirs() }
            val part = File(dir, asset.name + ".part")
            val target = File(dir, asset.name)
            operationCatching {
                val c = URL(asset.url).openConnection() as HttpURLConnection
                val downloadedFile: File = try {
                    c.connectTimeout = CONNECT_TIMEOUT_MS
                    c.readTimeout = READ_TIMEOUT_MS
                    if (c.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${c.responseCode}")
                    val total = c.contentLength.takeIf { it > 0 }?.toLong()
                    var done = 0L
                    var lastShown = -1L
                    c.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                // Throttled: a full percent, or a quarter megabyte without a total.
                                val shown = total?.let { done * 100 / it } ?: (done / (256 * 1024))
                                if (shown != lastShown) {
                                    lastShown = shown
                                    onProgress?.invoke(done, total)
                                }
                            }
                        }
                    }
                    if (done == 0L) error("empty download")
                    part.renameTo(target)
                    target
                } finally {
                    c.disconnect()
                }
                AdbOperationResult.Success(downloadedFile)
            }.getOrElse { error ->
                part.delete()
                AdbOperationResult.Failure(
                    message = "Downloading ${asset.name} failed",
                    suggestion = "Check the TV's network, then try again.",
                    cause = error,
                )
            }
        }
}
