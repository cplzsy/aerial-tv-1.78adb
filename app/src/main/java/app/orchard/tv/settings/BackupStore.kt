package app.orchard.tv.settings

import androidx.annotation.RequiresApi
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import app.orchard.tv.apps.AppCatalog
import app.orchard.tv.apps.AppNames
import app.orchard.tv.apps.SourceLooks
import app.orchard.tv.weather.Weather
import app.orchard.tv.wallpaper.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The owner's own setup in one JSON file, shared by the phone page (PhoneServer) and the TV's own
 * Backup & Restore in Control Center: every preference Aerial keeps for the setup — the Home
 * arrangement, the app and input names, the wallpaper and screensaver choices, the weather's place,
 * the PIN, the hidden and PIN-protected apps — and nothing of the device's state or the pictures.
 *
 * On the TV, backup files live in /sdcard/Documents/AerialBackup/ (shared external storage), so
 * they survive uninstalling Aerial:
 *  - Android 11+ (API 30+): the same folder, written directly; needs All Files Access
 *    (MANAGE_EXTERNAL_STORAGE), granted once in Settings -> Apps -> Aerial. Filesystem access is
 *    the one way backups stay readable after reinstalling Aerial.
 *  - Android 10 (API 29): written through the MediaStore Files collection; no permission.
 *  - Android 5-9 (API 21-28): the same folder, written directly; needs WRITE_EXTERNAL_STORAGE.
 * Exporting keeps the newest [KEEP] files and deletes the older ones, so the folder never fills up.
 */
object BackupStore {
    private const val TAG = "AerialBackup"
    /** The TV-side folder name, inside Documents (shared, kept on uninstall). */
    private const val DIR = "AerialBackup"
    /** Where the files are on Android 10: Documents/AerialBackup/, i.e. /sdcard/Documents/AerialBackup/.
     *  The trailing slash is required: MediaStore reads RELATIVE_PATH as a folder. */
    private val MEDIA_ROOT = Environment.DIRECTORY_DOCUMENTS + "/" + DIR + "/"

    /** The preferences a backup carries: the owner's setup and nothing of the device's state. */
    private val PREFS = listOf("settings", "layout", "wallpaper", "weather", "icon_library")
    /** The backup file's layout; a file with another is refused. */
    private const val FORMAT = 1
    /** A backup of the settings alone is small; a file much larger is not ours. */
    private const val MAX_FILE = 1024L * 1024
    /** How many backups the folder keeps: the newest one and the one before it. */
    private const val KEEP = 2

    /** One backup found in the folder, by name (with its time) and how to read it back. */
    data class Entry(val name: String, val uri: String, val path: String?)

    // ---------------------------------------------------------------- JSON, shared

    /** Builds the JSON a backup is made of. */
    fun buildJson(context: Context): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("app", runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" }.getOrDefault(""))
        root.put("exported_at", System.currentTimeMillis())
        root.put("device", Build.MODEL)
        val all = JSONObject()
        for (name in PREFS) {
            val o = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                when (v) {
                    is String -> o.put(k, v)
                    is Boolean -> o.put(k, v)
                    is Int -> o.put(k, v)
                    is Long -> o.put(k, v)
                    is Float -> o.put(k, v)
                    is Set<*> -> o.put(k, JSONArray(v.map { it.toString() }))
                    else -> {}
                }
            }
            all.put(name, o)
        }
        root.put("prefs", all)
        return root.toString()
    }

    /**
     * Reads a backup made with [buildJson] and writes it over the same preferences, then Home, the
     * wallpaper and the weather pick it up at once. Only the settings are restored, never the
     * pictures: those stay as they are on this TV. Returns whether it was accepted and applied.
     */
    fun restoreJson(context: Context, text: String): Boolean {
        if (text.length > MAX_FILE) return false
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return false
        if (root.optInt("format") != FORMAT) return false
        val prefsJson = root.optJSONObject("prefs") ?: return false
        for (name in PREFS) {
            val o = prefsJson.optJSONObject(name) ?: continue
            val edit = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            o.keys().forEach { k ->
                when (val v = o.get(k)) {
                    JSONObject.NULL -> {}
                    is String -> edit.putString(k, v)
                    is Boolean -> edit.putBoolean(k, v)
                    is Int -> edit.putInt(k, v)
                    is Long -> edit.putLong(k, v)
                    is Double -> edit.putFloat(k, v.toFloat())
                    is JSONArray -> edit.putStringSet(k, (0 until v.length()).map { v.getString(it) }.toSet())
                    else -> {}
                }
            }
            edit.apply()
        }
        // On the main thread, so the restored settings are the ones Home, the wallpaper and the
        // weather show from now on.
        runBlocking(Dispatchers.Main) {
            AerialSettings.reload()
            AppNames.reload()
            SourceLooks.reload()
            Wallpapers.reload()
            Weather.reload()
            AppCatalog.restoreHome?.invoke()
        }
        return true
    }

    // ---------------------------------------------------------------- TV storage

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun newName(): String = "aerial-backup-${stamp()}.json"

    /**
     * Exports the settings to the shared folder and keeps the newest [KEEP] files. Returns the
     * entry written, or a failure (a permission problem, storage full, ...).
     *
     * On Android 11+ the folder is written through the filesystem, which needs All Files Access
     * (MANAGE_EXTERNAL_STORAGE) — the one way a backup survives uninstalling Aerial and is still
     * readable afterwards (MediaStore drops the app's ownership of its non-media files on
     * uninstall, so a restored install could not see them again). Android 10 uses MediaStore, and
     * Android 5-9 uses the filesystem with WRITE_EXTERNAL_STORAGE.
     */
    fun export(context: Context): Result<Entry> = runCatching {
        val json = buildJson(context)
        val entry = when {
            Build.VERSION.SDK_INT >= 30 -> {
                if (!Environment.isExternalStorageManager()) error("all files access not granted")
                exportFile(context, json)
            }
            Build.VERSION.SDK_INT >= 29 -> exportMedia(context, json)
            else -> exportFile(context, json)
        }
        Log.i(TAG, "exported ${entry.name}")
        entry
    }

    /** Creates /sdcard/Documents/AerialBackup/ when it is missing, so an export always has a home. */
    private fun ensureFolder(): File {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            // On Android 10+ scoped storage may refuse the filesystem mkdir; MediaStore creates
            // the RELATIVE_PATH folder on insert in that case, so this is only a warning there.
            Log.w(TAG, "could not create ${dir.path}")
        }
        return dir
    }

    /** Only called from [export] when the device is Android 10+ (API 29+). */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportMedia(context: Context, json: String): Entry {
        val name = newName()
        val resolver = context.contentResolver
        // Create the folder first (best effort — scoped storage may refuse the filesystem mkdir,
        // where MediaStore still creates the RELATIVE_PATH folder on insert). A few TV firmwares
        // only map an already-existing folder, so never rely on the insert alone.
        ensureFolder()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, MEDIA_ROOT)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), values)
            ?: error("no MediaStore row")
        try {
            resolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                ?: error("cannot write $name")
        } finally {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        trimMedia(context)
        return Entry(name, uri.toString(), null)
    }

    private fun exportFile(context: Context, json: String): Entry {
        val name = newName()
        // The same folder as on Android 10+: /sdcard/Documents/AerialBackup/.
        val dir = ensureFolder()
        if (!dir.exists()) error("cannot create ${dir.path}")
        val f = File(dir, name)
        f.writeText(json)
        trimFile(dir)
        return Entry(name, "", f.path)
    }

    /** The backups in the folder, newest first. Empty when All Files Access is not granted. */
    fun list(context: Context): List<Entry> = when {
        Build.VERSION.SDK_INT >= 30 -> if (Environment.isExternalStorageManager()) listFile(context) else emptyList()
        Build.VERSION.SDK_INT >= 29 -> listMedia(context)
        else -> listFile(context)
    }

    /** Only called from [list] when the device is Android 10+ (API 29+). */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun listMedia(context: Context): List<Entry> {
        val out = ArrayList<Entry>()
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("aerial-backup-%")
        resolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, selection, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iRel = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (c.moveToNext()) {
                // RELATIVE_PATH comes back with its trailing slash; MEDIA_ROOT has one too.
                val rel = c.getString(iRel)
                if (rel != null && rel.trimEnd('/') != MEDIA_ROOT.trimEnd('/')) continue
                val id = c.getLong(iId)
                val name = c.getString(iName) ?: continue
                out += Entry(name, MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL).buildUpon().appendPath(id.toString()).build().toString(), null)
            }
        }
        return out
    }

    private fun listFile(context: Context): List<Entry> {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.startsWith("aerial-backup-") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { Entry(it.name, "", it.path) }
            ?: emptyList()
    }

    /** Restores the chosen backup; the reloads happen on the main thread inside [restoreJson]. */
    fun restore(context: Context, entry: Entry): Result<Unit> = runCatching {
        val text = if (entry.path != null) {
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) error("all files access not granted")
            File(entry.path).readText()
        } else context.contentResolver.openInputStream(android.net.Uri.parse(entry.uri))?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("cannot read ${entry.name}")
        if (!restoreJson(context, text)) error("not a valid backup")
        Log.i(TAG, "restored ${entry.name}")
    }

    // ---------------------------------------------------------------- keep the newest KEEP

    /** Only called from [exportMedia] when the device is Android 10+ (API 29+). */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun trimMedia(context: Context) {
        val all = listMedia(context)
        val resolver = context.contentResolver
        for (old in all.drop(KEEP)) {
            runCatching { resolver.delete(android.net.Uri.parse(old.uri), null, null) }
                .onFailure { Log.w(TAG, "could not delete ${old.name}", it) }
        }
    }

    private fun trimFile(dir: File) {
        val all = dir.listFiles { f -> f.isFile && f.name.startsWith("aerial-backup-") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() } ?: return
        for (old in all.drop(KEEP)) {
            runCatching { old.delete() }.onFailure { Log.w(TAG, "could not delete ${old.name}", it) }
        }
    }
}
