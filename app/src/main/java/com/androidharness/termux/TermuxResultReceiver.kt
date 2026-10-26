package com.androidharness.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Receives the PendingIntent Termux fires when a command finishes, and
 * hands the result to whoever is waiting for that request id.
 */
class TermuxResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        if (requestId == null) {
            Log.w(TAG, "result with no request id, ignoring")
            return
        }
        val bundle: Bundle? = intent.getBundleExtra(TermuxConstants.EXTRA_RESULT_BUNDLE)
        if (bundle == null) {
            Log.w(TAG, "result bundle missing for $requestId")
            TermuxBridge.deliver(
                requestId,
                CommandResult(
                    exitCode = null,
                    stdout = "",
                    stderr = "",
                    errmsg = "Termux returned no result bundle.",
                ),
            )
            return
        }

        TermuxBridge.deliver(
            requestId,
            CommandResult(
                exitCode = if (bundle.containsKey(TermuxConstants.RESULT_EXIT_CODE)) {
                    bundle.getInt(TermuxConstants.RESULT_EXIT_CODE)
                } else {
                    null
                },
                stdout = bundle.getString(TermuxConstants.RESULT_STDOUT).orEmpty(),
                stderr = bundle.getString(TermuxConstants.RESULT_STDERR).orEmpty(),
                errmsg = bundle.getString(TermuxConstants.RESULT_ERRMSG).orEmpty(),
            ),
        )
    }

    companion object {
        private const val TAG = "TermuxResult"
        const val EXTRA_REQUEST_ID = "com.androidharness.REQUEST_ID"
    }
}
