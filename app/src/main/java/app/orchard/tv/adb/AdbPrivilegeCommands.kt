package app.orchard.tv.adb

/**
 * The command list of the ADB privilege pipeline (ported from skyadb's AdbPrivilegeCommands, with
 * the components already pointing at Aerial — the source project's constants are this package).
 *
 * Commands are the bodies to run through the ADB shell (without the `adb shell ` prefix); the
 * result model prepends that prefix when reporting.
 */
object AdbPrivilegeCommands {

    const val TARGET_PACKAGE = "app.orchard.tv"
    const val MAIN_ACTIVITY = "app.orchard.tv/.MainActivity"
    const val CONFIG_RECEIVER = "app.orchard.tv/.guard.ConfigReceiver"
    const val REVIVAL_LISTENER = "app.orchard.tv/app.orchard.tv.guard.RevivalListener"
    const val AERIAL_DREAM = "app.orchard.tv/app.orchard.tv.dream.AerialDream"
    const val HOME_GUARD_SERVICE = "app.orchard.tv/app.orchard.tv.guard.HomeGuardService"

    /** Stage 1: the ADB-only permissions, run one by one (a failure never stops the rest). */
    val grantCommands: List<String> = listOf(
        "pm grant $TARGET_PACKAGE android.permission.ACCESS_FINE_LOCATION",
        "pm grant $TARGET_PACKAGE android.permission.WRITE_SECURE_SETTINGS",
        "appops set $TARGET_PACKAGE SYSTEM_ALERT_WINDOW allow",
        "appops set $TARGET_PACKAGE WRITE_SETTINGS allow",
        "appops set $TARGET_PACKAGE REQUEST_INSTALL_PACKAGES allow",
        "appops set $TARGET_PACKAGE GET_USAGE_STATS allow",
        "appops set $TARGET_PACKAGE android:get_usage_stats allow",
        "appops set $TARGET_PACKAGE ACCESS_RESTRICTED_SETTINGS allow",
        "cmd notification allow_listener $REVIVAL_LISTENER",
        "appops set $TARGET_PACKAGE APP_AUTO_START allow",
        "appops set $TARGET_PACKAGE APP_ASSOC_START allow",
        "appops set $TARGET_PACKAGE AUTO_START allow",
        "appops set $TARGET_PACKAGE ASSOC_START allow",
    )

    /** Stage 2: cold-start once, wait, then compile the package for speed. */
    const val LAUNCH_COMMAND = "am start -W -n $MAIN_ACTIVITY"
    const val COMPILE_COMMAND = "cmd package compile -m speed -f $TARGET_PACKAGE"

    /** Stage 3: screensaver takeover — only when the screensaver is already enabled. */
    const val SCREENSAVER_QUERY_COMMAND = "settings get secure screensaver_enabled"
    const val SCREENSAVER_COMPONENT_COMMAND =
        "settings put secure screensaver_components $AERIAL_DREAM"
    const val SCREENSAVER_CONFIG_COMMAND =
        "am broadcast -a app.orchard.tv.CONFIG -n $CONFIG_RECEIVER --ez own_screensaver true"

    /** Stage 4: keep ADB open — only when the caller opted in. */
    const val KEEP_ADB_COMMAND =
        "am broadcast -a app.orchard.tv.CONFIG -n $CONFIG_RECEIVER --ez keep_adb true"

    /** Stage 6: make Aerial the default home, with the legacy fallback for pre-role Android. */
    const val DEFAULT_HOME_ROLE_COMMAND =
        "cmd role add-role-holder --user 0 android.app.role.HOME $TARGET_PACKAGE"
    const val DEFAULT_HOME_FALLBACK_COMMAND =
        "cmd package set-home-activity --user 0 $MAIN_ACTIVITY"

    /** Stage 7: home guard — read the current accessibility services, append ours, enable. */
    const val ACCESSIBILITY_QUERY_COMMAND =
        "settings get secure enabled_accessibility_services"
    const val ACCESSIBILITY_ENABLE_COMMAND = "settings put secure accessibility_enabled 1"

    /** How long to wait after cold-starting before compiling. */
    const val COMPILE_WAIT_MILLIS = 5_000L

    /** Stage 5 requires `am start -W` to print this marker to count as a success. */
    const val STATUS_OK_MARKER = "Status: ok"

    /** Whole-stage skip reasons (backend log lines, no UI strings involved). */
    const val SKIP_REASON_SCREENSAVER = "screensaver not enabled (screensaver_enabled != 1)"
    const val SKIP_REASON_KEEP_ADB = "keep ADB off (caller opted out)"

    /**
     * Stage 7: merge the current accessibility services value with ours. A blank or "null" value
     * becomes just our component; anything else keeps the existing services and appends ours with a
     * colon, so the pipeline never clears services the user already enabled.
     */
    fun buildAccessibilityServicesValue(original: String): String {
        val trimmed = original.trim()
        return if (trimmed.isBlank() || trimmed == "null") {
            HOME_GUARD_SERVICE
        } else {
            "$trimmed:$HOME_GUARD_SERVICE"
        }
    }

    /** The command as reported: always with the `adb shell ` prefix. */
    fun displayCommand(command: String): String = "adb shell $command"
}
