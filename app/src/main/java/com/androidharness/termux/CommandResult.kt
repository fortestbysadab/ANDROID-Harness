package com.androidharness.termux

/** What came back from one command run inside Termux. */
data class CommandResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    /** Termux's own error, as opposed to the command's. */
    val errmsg: String,
    val timedOut: Boolean = false,
) {
    val succeeded: Boolean
        get() = !timedOut && errmsg.isEmpty() && exitCode == 0

    /**
     * Termux reports a refused request through errmsg rather than a
     * failure of the intent itself, so a rejection looks identical to a
     * command that produced nothing. This is the one case worth naming,
     * because its remedy is a setting the owner has to change by hand.
     */
    val blockedByExternalAppsPolicy: Boolean
        get() = errmsg.contains("allow-external-apps", ignoreCase = true)
}
