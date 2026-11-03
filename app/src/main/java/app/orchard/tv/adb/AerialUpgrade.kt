package app.orchard.tv.adb

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A downloadable Aerial APK from one of the upgrade sources. */
data class AerialFile(
    val name: String,
    val url: String,
    /** Cookie the download needs; only the fnOS NAS source sets it. */
    val cookie: String? = null,
)

/** Where an Aerial build comes from. */
enum class AerialSource { Nas, Github, GithubCustom }

/**
 * The "Aerial upgrade install" backend: lists Aerial APKs from the local fnOS NAS share and the
 * GitHub sources (official release + custom release). Downloads land in <files>/aerial-upgrades/;
 * the caller installs the returned file with AdbService.manager.install.
 */
object AerialUpgrade {

    private const val TAG = "AerialAdb"

    // The owner's LAN NAS share (fnOS), carried over from skyadb's Aerial installer.
    private const val NAS_SHARE = "http://192.168.5.218:5666/s/41412d18807c4e8499"
    private const val GITHUB_RELEASE_URL =
        "https://api.github.com/repos/pengchujin/aerial-tv/releases/latest"
    // The custom GitHub build source; points at the owner's custom release repository.
    // Web page: https://github.com/cplzsy/aerial-tv-1.78adb/releases/latest
    private const val GITHUB_CUSTOM_URL =
        "https://api.github.com/repos/cplzsy/aerial-tv-1.78adb/releases/latest"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    // fnOS share signing constants, verified against skyadb's implementation.
    private const val SIGN_SECRET = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh"
    private const val API_KEY = "814&d6470861a4cfbbb4fe2fd3f\$6581f6"
    private const val MAX_DIR_DEPTH = 3

    private data class NasEntry(val name: String, val path: String, val fileId: Long)

    private val shareDataRegex =
        Regex(
            """<script[^>]*id=["']share-data["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )

    /** Lists Aerial APKs from [source]. NAS lists from the local fnOS share. */
    suspend fun fetch(source: AerialSource): AdbOperationResult<List<AerialFile>> =
        withContext(Dispatchers.IO) {
            when (source) {
                AerialSource.Github -> fetchGithub(GITHUB_RELEASE_URL)
                AerialSource.GithubCustom -> fetchGithub(GITHUB_CUSTOM_URL)
                AerialSource.Nas -> fetchFnos()
            }
        }

    /**
     * Downloads [file] into <files>/aerial-upgrades/ (a .part temp file is renamed on success).
     * [onProgress] reports throttled progress on a background thread.
     */
    suspend fun download(
        appContext: Context,
        file: AerialFile,
        onProgress: ((done: Long, total: Long?) -> Unit)? = null,
    ): AdbOperationResult<File> =
        withContext(Dispatchers.IO) {
            val dir = File(appContext.filesDir, "aerial-upgrades").apply { mkdirs() }
            val part = File(dir, file.name + ".part")
            val target = File(dir, file.name)
            operationCatching {
                val c = URL(file.url).openConnection() as HttpURLConnection
                val downloadedFile: File = try {
                    c.connectTimeout = CONNECT_TIMEOUT_MS
                    c.readTimeout = READ_TIMEOUT_MS
                    file.cookie?.let { c.setRequestProperty("Cookie", it) }
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
                    message = "Download failed: ${file.name}",
                    suggestion = "Check the network, then try again.",
                    cause = error,
                )
            }
        }

    private suspend fun fetchGithub(url: String): AdbOperationResult<List<AerialFile>> =
        withContext(Dispatchers.IO) {
            // operationCatching is mandatory here: a 403/SSL failure must surface as a Failure row,
            // not as an uncaught exception that crashes the page (observed on TV 35: tapping
            // "Confirm upgrade" crashed the app because fetchGithub alone lacked the wrapper).
            operationCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                val files: List<AerialFile> = try {
                    c.requestMethod = "GET"
                    // GitHub's API rejects requests without a user agent.
                    c.setRequestProperty("User-Agent", "Aerial/1.77")
                    c.setRequestProperty("Accept", "application/vnd.github+json")
                    c.connectTimeout = CONNECT_TIMEOUT_MS
                    c.readTimeout = READ_TIMEOUT_MS
                    if (c.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${c.responseCode} from GitHub")
                    val text = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val assets = JSONObject(text).optJSONArray("assets") ?: JSONArray()
                    buildList {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name")
                            val url = asset.optString("browser_download_url")
                            if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                                add(AerialFile(name, url))
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                }
                if (files.isEmpty()) error("No Aerial APK in this GitHub release")
                AdbOperationResult.Success(files)
            }.getOrElse { error ->
                AdbOperationResult.Failure(
                    message = "Cannot fetch the GitHub release",
                    suggestion = "Check the network, then try again.",
                    cause = error,
                )
            }
        }

    /** fnOS share: token from the page, recursive list, then a real URL per file. */
    private suspend fun fetchFnos(): AdbOperationResult<List<AerialFile>> =
        withContext(Dispatchers.IO) {
            try {
                val origin = NAS_SHARE.substringBefore("/s/").trimEnd('/')
                val shareId = NAS_SHARE.substringAfterLast("/s/").trim()
                val page = httpGet("$origin/s/$shareId", cookie = null)
                val token = parseShareToken(page) ?: error("No share token in NAS page")
                val cookie = "$shareId=$token"
                val entries = mutableListOf<NasEntry>()
                collectFnos(origin, shareId, path = "/", fileId = null, depth = 0, token, cookie, entries)
                if (entries.isEmpty()) error("No APK in the NAS share")
                val files = mutableListOf<AerialFile>()
                for (entry in entries) {
                    val url = fnosDownloadUrl(origin, shareId, entry, token, cookie) ?: continue
                    files += AerialFile(entry.name, url, cookie)
                }
                if (files.isEmpty()) error("No downloadable APK in the NAS share")
                AdbOperationResult.Success(files.sortedBy { it.name })
            } catch (error: Exception) {
                AdbOperationResult.Failure(
                    message = "Cannot reach the LAN NAS share",
                    suggestion = "Check that the NAS is online and reachable from the TV.",
                    cause = error,
                )
            }
        }

    private fun httpGet(url: String, cookie: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            if (cookie != null) c.setRequestProperty("Cookie", cookie)
            if (c.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** fnOS POST with Auth/authx/Cookie headers; null when the call or the code fails. */
    private fun fnosPostJson(
        origin: String,
        apiPath: String,
        body: JSONObject,
        token: String,
        cookie: String,
    ): JSONObject? {
        val bodyString = body.toString()
        val authx = buildAuthx(apiPath, bodyString)
        val c = URL(origin + apiPath).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Auth", token)
            c.setRequestProperty("authx", authx)
            c.setRequestProperty("Cookie", cookie)
            c.outputStream.use { it.write(bodyString.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode != HttpURLConnection.HTTP_OK) return null
            val payload = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
            return if (json.optInt("code", -1) == 0) json else null
        } catch (error: Exception) {
            return null
        } finally {
            c.disconnect()
        }
    }

    /** Recursively collects .apk entries under a share directory (depth-limited). */
    private fun collectFnos(
        origin: String,
        shareId: String,
        path: String,
        fileId: Long?,
        depth: Int,
        token: String,
        cookie: String,
        out: MutableList<NasEntry>,
    ) {
        if (depth > MAX_DIR_DEPTH) return
        val body = JSONObject().put("shareId", shareId).put("path", path)
        if (fileId != null) body.put("fileId", fileId)
        val json = fnosPostJson(origin, "/s/$shareId/api/v1/share/list", body, token, cookie) ?: return
        val files = json.optJSONObject("data")?.optJSONArray("files") ?: return
        for (i in 0 until files.length()) {
            val entry = files.optJSONObject(i) ?: continue
            val name = entry.optString("file").trim()
            if (name.isEmpty()) continue
            val entryId = entry.optLong("fileId", -1L)
            if (entry.optInt("isDir", 0) == 1) {
                val childPath = if (path == "/") "/$name" else "$path/$name"
                collectFnos(origin, shareId, childPath, entryId, depth + 1, token, cookie, out)
            } else if (name.endsWith(".apk", ignoreCase = true)) {
                val filePath =
                    if (entry.optString("path").isNullOrBlank() || entry.optString("path") == "/") {
                        "/$name"
                    } else {
                        entry.optString("path").trimEnd('/') + "/" + name
                    }
                out += NasEntry(name, filePath, entryId)
            }
        }
    }

    /** Exchanges a file entry for its real download URL (needs token + cookie). */
    private fun fnosDownloadUrl(
        origin: String,
        shareId: String,
        file: NasEntry,
        token: String,
        cookie: String,
    ): String? {
        val body =
            JSONObject()
                .put(
                    "files",
                    JSONArray().put(JSONObject().put("path", file.path).put("fileId", file.fileId)),
                )
                .put("shareId", shareId)
                .put("downloadFilename", file.name)
        val json = fnosPostJson(origin, "/s/$shareId/api/v1/share/download", body, token, cookie) ?: return null
        val path = json.optJSONObject("data")?.optString("path").orEmpty()
        return if (path.isBlank()) null else origin + path
    }

    /** Extracts the share token embedded in the share page HTML. */
    private fun parseShareToken(html: String): String? {
        val match = shareDataRegex.find(html) ?: return null
        return runCatching {
            JSONObject(match.groupValues[1]).optJSONObject("data")?.optString("token")
        }.getOrNull()
    }

    /** Builds the authx signature header (MD5, verified against skyadb). */
    private fun buildAuthx(apiPath: String, bodyString: String): String {
        val nonce = (Random().nextInt(900_000) + 100_000).toString()
        val timestamp = System.currentTimeMillis().toString()
        val signInput =
            "${SIGN_SECRET}_${apiPath}_${nonce}_${timestamp}_${md5Hex(bodyString)}_$API_KEY"
        return "nonce=$nonce&timestamp=$timestamp&sign=${md5Hex(signInput)}"
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }
}
