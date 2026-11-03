package app.orchard.tv.adb

/**
 * The fixed stages of the ADB privilege pipeline, kept in execution order. (skyadb ships the same
 * enum; Aerial's backend serializes the names directly since there is no UI to map titles.)
 */
enum class PrivilegeStep {
    GrantPermissions,
    CompileOptimize,
    Screensaver,
    KeepAdb,
    LaunchAerial,
    DefaultHome,
    HomeGuard,
}

/** The outcome of one privilege command. */
enum class PrivilegeCommandState {
    SUCCESS,
    FAILED,
    SKIPPED,
}

/** One command of the pipeline: the full `adb shell ...` line, its state and a short output. */
data class PrivilegeCommandResult(
    val command: String,
    val state: PrivilegeCommandState,
    val output: String = "",
)

/** One stage: its commands, or a whole-stage skip with a reason. */
data class PrivilegeStepResult(
    val step: PrivilegeStep,
    val commands: List<PrivilegeCommandResult>,
    val skippedReason: String? = null,
) {
    val skipped: Boolean get() = skippedReason != null
}

/** The whole run: per-stage results plus the totals the caller reports. */
data class AdbPrivilegeReport(
    val steps: List<PrivilegeStepResult>,
) {
    val failedCount: Int
        get() = steps.sumOf { step -> step.commands.count { it.state == PrivilegeCommandState.FAILED } }

    val succeededCount: Int
        get() = steps.sumOf { step -> step.commands.count { it.state == PrivilegeCommandState.SUCCESS } }

    val skippedCount: Int
        get() = steps.sumOf { step -> if (step.skipped) 1 else 0 }
}
