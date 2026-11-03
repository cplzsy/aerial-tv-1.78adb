package app.orchard.tv.adb

import app.orchard.tv.adb.AdbManager.AdbSessionKind
import app.orchard.tv.system.DiagLog
import kotlinx.coroutines.delay

/**
 * The ADB privilege pipeline (ported from skyadb's AdbRepository.runAdbPrivilege): seven stages run
 * one command at a time over the open session — grant ADB permissions, cold-start and compile,
 * take over the screensaver, keep ADB open, relaunch Aerial, become the default home, enable the
 * home-guard accessibility service.
 *
 * A failed command never aborts the pipeline: it is recorded as FAILED and the next command runs.
 * Whole stages can be SKIPPED with a reason (screensaver disabled, keep-ADB opted out). The report
 * is always returned as a Success unless there is no session at all.
 */
class AdbPrivilege(private val manager: AdbManager) {

    suspend fun run(keepAdbEnabled: Boolean): AdbOperationResult<AdbPrivilegeReport> {
        // Every stage is a shell command, so the pipeline needs a live session; with none, refuse
        // up front (Aerial's backend has no fastboot session, so there is no shell-less variant).
        if (manager.sessionKind() == AdbSessionKind.None) {
            return AdbOperationResult.Failure(
                message = "no ADB session",
                suggestion = "connect to the device first (op=connect)",
            )
        }

        val steps = mutableListOf<PrivilegeStepResult>()

        // Stage 1: grant the ADB-only permissions; each command stands alone.
        steps += PrivilegeStepResult(
            step = PrivilegeStep.GrantPermissions,
            commands = AdbPrivilegeCommands.grantCommands.map { runCommand(it) },
        )

        // Stage 2: cold-start once, wait for it to settle, then compile the package.
        val launchResult = runCommand(AdbPrivilegeCommands.LAUNCH_COMMAND)
        delay(AdbPrivilegeCommands.COMPILE_WAIT_MILLIS)
        val compileResult = runCommand(AdbPrivilegeCommands.COMPILE_COMMAND)
        steps += PrivilegeStepResult(
            step = PrivilegeStep.CompileOptimize,
            commands = listOf(launchResult, compileResult),
        )

        // Stage 3: screensaver takeover; the whole stage is skipped unless the TV already has a
        // screensaver enabled.
        val screensaverQuery = runCommand(AdbPrivilegeCommands.SCREENSAVER_QUERY_COMMAND)
        val screensaverEnabled =
            screensaverQuery.state == PrivilegeCommandState.SUCCESS &&
                screensaverQuery.output.trim() == "1"
        val screensaverCommands =
            if (screensaverEnabled) {
                listOf(
                    runCommand(AdbPrivilegeCommands.SCREENSAVER_COMPONENT_COMMAND),
                    runCommand(AdbPrivilegeCommands.SCREENSAVER_CONFIG_COMMAND),
                )
            } else {
                emptyList()
            }
        steps += PrivilegeStepResult(
            step = PrivilegeStep.Screensaver,
            commands = listOf(screensaverQuery) + screensaverCommands,
            skippedReason = if (screensaverEnabled) null else AdbPrivilegeCommands.SKIP_REASON_SCREENSAVER,
        )

        // Stage 4: keep ADB open; the whole stage is skipped when the caller turned the switch off.
        val keepAdbCommands =
            if (keepAdbEnabled) {
                listOf(runCommand(AdbPrivilegeCommands.KEEP_ADB_COMMAND))
            } else {
                emptyList()
            }
        steps += PrivilegeStepResult(
            step = PrivilegeStep.KeepAdb,
            commands = keepAdbCommands,
            skippedReason = if (keepAdbEnabled) null else AdbPrivilegeCommands.SKIP_REASON_KEEP_ADB,
        )

        // Stage 5: relaunch Aerial; `am start -W` only counts with its "Status: ok" marker.
        steps += PrivilegeStepResult(
            step = PrivilegeStep.LaunchAerial,
            commands = listOf(
                runCommand(AdbPrivilegeCommands.LAUNCH_COMMAND, requireStatusOk = true),
            ),
        )

        // Stage 6: become the default home; on failure retry with the legacy fallback.
        val homeRoleResult = runCommand(AdbPrivilegeCommands.DEFAULT_HOME_ROLE_COMMAND)
        val homeCommands =
            if (homeRoleResult.state == PrivilegeCommandState.SUCCESS) {
                listOf(homeRoleResult)
            } else {
                listOf(
                    homeRoleResult,
                    runCommand(AdbPrivilegeCommands.DEFAULT_HOME_FALLBACK_COMMAND),
                )
            }
        steps += PrivilegeStepResult(
            step = PrivilegeStep.DefaultHome,
            commands = homeCommands,
        )

        // Stage 7: home guard — read the current accessibility services, append ours (never wiping
        // what the user enabled), then turn accessibility on.
        val accessibilityQuery = runCommand(AdbPrivilegeCommands.ACCESSIBILITY_QUERY_COMMAND)
        val mergedValue = AdbPrivilegeCommands.buildAccessibilityServicesValue(
            accessibilityQuery.output.trim(),
        )
        steps += PrivilegeStepResult(
            step = PrivilegeStep.HomeGuard,
            commands = listOf(
                accessibilityQuery,
                runCommand("settings put secure enabled_accessibility_services '$mergedValue'"),
                runCommand(AdbPrivilegeCommands.ACCESSIBILITY_ENABLE_COMMAND),
            ),
        )

        val report = AdbPrivilegeReport(steps)
        if (report.failedCount > 0) {
            DiagLog.i(
                "ADB privilege: ${report.succeededCount} ok, ${report.failedCount} failed, " +
                    "${report.skippedCount} skipped (target ${AdbPrivilegeCommands.TARGET_PACKAGE})",
            )
        }
        return AdbOperationResult.Success(report)
    }

    /** Runs one privilege command; a failure is recorded, never thrown, so the pipeline continues. */
    private suspend fun runCommand(
        command: String,
        requireStatusOk: Boolean = false,
    ): PrivilegeCommandResult {
        val display = AdbPrivilegeCommands.displayCommand(command)
        return when (val result = manager.shell(command)) {
            is AdbOperationResult.Success -> {
                val shellResult = result.data
                val combinedOutput =
                    listOf(shellResult.output, shellResult.errorOutput)
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                val ok =
                    shellResult.exitCode == 0 &&
                        (!requireStatusOk ||
                            combinedOutput.contains(AdbPrivilegeCommands.STATUS_OK_MARKER))
                PrivilegeCommandResult(
                    command = display,
                    state = if (ok) PrivilegeCommandState.SUCCESS else PrivilegeCommandState.FAILED,
                    output = combinedOutput.take(MaxPrivilegeOutputLength),
                )
            }
            is AdbOperationResult.Failure -> PrivilegeCommandResult(
                command = display,
                state = PrivilegeCommandState.FAILED,
                output =
                    listOf(result.message, result.suggestion)
                        .filter { it.isNotBlank() }
                        .joinToString(" | ")
                        .take(MaxPrivilegeOutputLength),
            )
        }
    }

    private companion object {
        const val MaxPrivilegeOutputLength = 300
    }
}
