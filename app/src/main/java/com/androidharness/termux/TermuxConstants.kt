package com.androidharness.termux

/**
 * Names defined by Termux, not by us.
 *
 * Termux publishes a `termux-shared` library carrying these as constants
 * and recommends depending on it rather than hardcoding strings. We do not,
 * deliberately: it is a large dependency pulled from JitPack for a handful
 * of string literals, and this app must keep building in CI without a
 * third-party Maven repository. The trade is that a rename upstream breaks
 * us silently, so these are pinned to the documented v0.109+ protocol and
 * checked by the diagnostics screen rather than assumed.
 */
object TermuxConstants {
    const val PACKAGE = "com.termux"
    const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    /** Bundle of results Termux puts back into the PendingIntent. */
    const val EXTRA_RESULT_BUNDLE = "result"
    const val RESULT_STDOUT = "stdout"
    const val RESULT_STDERR = "stderr"
    const val RESULT_EXIT_CODE = "exitCode"
    const val RESULT_ERR = "err"
    const val RESULT_ERRMSG = "errmsg"

    /** Absolute paths inside Termux; it has no PATH lookup for us. */
    const val PREFIX = "/data/data/com.termux/files/usr"
    const val HOME = "/data/data/com.termux/files/home"
    const val BIN = "$PREFIX/bin"
}
