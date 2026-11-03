package app.orchard.tv.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The ADB backend's command entry, driven from a computer over ADB:
 *
 * ```
 * adb shell am broadcast -a app.orchard.tv.ADB -n app.orchard.tv/.adb.AdbCommandReceiver \
 *     --es op connect --es host 192.168.1.35 --ei port 5555
 * ```
 *
 * The manifest requires the sender to hold WRITE_SECURE_SETTINGS — the ADB shell has it, ordinary
 * apps cannot get it — so no other app on the TV can drive this backend (the same trust model as
 * ConfigReceiver). The result of an op that finishes within the broadcast window comes back as the
 * broadcast result, which `am broadcast` prints; every result also lands in logcat under the
 * AerialAdb tag, where long outputs (apps, shell, scan) can be read.
 */
class AdbCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ADB) return
        if (!AdbService.supported()) {
            resultData = "error: ADB backend needs Android 6.0+"
            return
        }
        val op = intent.getStringExtra("op")?.takeIf { it.isNotBlank() }
            ?: run {
                resultData = "error: missing op"
                return
            }
        AdbService.init(context)

        val params = buildMap {
            for (key in EXTRA_KEYS) {
                intent.getStringExtra(key)?.let { put(key, it) }
            }
        }

        // An op can take seconds (connect, install, scan). Run it off the main thread and finish the
        // broadcast only when it is done, so `am broadcast` prints the result; the launcher process
        // stays alive past the broadcast window, so even a long install completes.
        val pending = goAsync()
        Thread {
            try {
                val result = runCatching { AdbService.executeBlocking(op, params) }
                    .getOrElse { error -> "error: ${error.javaClass.simpleName} ${error.message}" }
                pending.resultData = result
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_ADB = "app.orchard.tv.ADB"
        private val EXTRA_KEYS = listOf("host", "port", "code", "name", "command", "file", "pkg", "keep_adb")
    }
}
