package com.androidharness.termux

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Why the Termux connection is not usable, and what the owner can do.
 *
 * Four distinct causes produce the same symptom - nothing happens - so
 * each is detected separately. Guessing between them is what makes this
 * integration feel broken.
 */
enum class TermuxReadiness {
    READY,
    NOT_INSTALLED,
    UNSUPPORTED_BUILD,
    PERMISSION_NOT_GRANTED,

    /**
     * Everything on our side is correct. Whether `allow-external-apps` is
     * set can only be learned by trying: the property lives in Termux's
     * private data directory, which another app cannot read.
     */
    UNVERIFIED,
}

data class TermuxState(
    val readiness: TermuxReadiness,
    val detail: String,
    val remedy: String = "",
) {
    val usable: Boolean
        get() = readiness == TermuxReadiness.READY || readiness == TermuxReadiness.UNVERIFIED
}

object TermuxStatus {

    fun check(context: Context): TermuxState {
        if (!installed(context)) {
            return TermuxState(
                TermuxReadiness.NOT_INSTALLED,
                "Termux is not installed.",
                "Install Termux from F-Droid or its GitHub releases. The " +
                    "Google Play build will not work: it is a separate fork " +
                    "with the RUN_COMMAND interface removed.",
            )
        }

        if (!serviceAvailable(context)) {
            // Termux is present but the service is not resolvable. The
            // overwhelmingly likely cause is the Play Store fork, which
            // rolled back ten releases and dropped this entry point.
            return TermuxState(
                TermuxReadiness.UNSUPPORTED_BUILD,
                "This Termux build does not expose RunCommandService.",
                "You almost certainly have the Google Play build. Uninstall " +
                    "it and install Termux from F-Droid or GitHub. The " +
                    "signatures differ, so it cannot be upgraded in place.",
            )
        }

        if (!permissionGranted(context)) {
            return TermuxState(
                TermuxReadiness.PERMISSION_NOT_GRANTED,
                "Permission to run commands in Termux has not been granted.",
                "Android Settings > Apps > Android Harness > Permissions > " +
                    "Additional permissions > Run commands in Termux " +
                    "environment.",
            )
        }

        return TermuxState(
            TermuxReadiness.UNVERIFIED,
            "Termux is installed and the permission is granted.",
            "Whether allow-external-apps is enabled can only be confirmed " +
                "by running a command, because the setting lives in " +
                "Termux's private files.",
        )
    }

    fun installed(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(TermuxConstants.PACKAGE, 0)
            true
        } catch (notFound: PackageManager.NameNotFoundException) {
            false
        }

    /** Does this Termux build actually expose the service we need? */
    fun serviceAvailable(context: Context): Boolean {
        val intent = Intent().apply {
            setClassName(TermuxConstants.PACKAGE, TermuxConstants.RUN_COMMAND_SERVICE)
            action = TermuxConstants.ACTION_RUN_COMMAND
        }
        return context.packageManager.resolveService(intent, 0) != null
    }

    fun permissionGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, TermuxConstants.PERMISSION_RUN_COMMAND) ==
            PackageManager.PERMISSION_GRANTED
}
