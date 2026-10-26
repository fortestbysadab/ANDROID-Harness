package com.androidharness.termux

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs a command inside Termux and returns its output.
 *
 * This is the whole of the app's dependency on Termux. Everything else
 * must reach it through the CodeSandbox interface; nothing else may import
 * this package.
 *
 * The exchange is asynchronous by Termux's design: the command is started
 * by an intent to a service, and the result arrives later through a
 * PendingIntent we supply. Callbacks are matched by request id.
 */
object TermuxBridge {

    private const val TAG = "TermuxBridge"
    private val requestCodes = AtomicInteger(1000)
    private val waiting = ConcurrentHashMap<String, (CommandResult) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /**
     * @param executable absolute path inside Termux; there is no PATH lookup
     * @param onResult always called exactly once, on the main thread
     */
    fun run(
        context: Context,
        executable: String,
        arguments: List<String> = emptyList(),
        workingDirectory: String = TermuxConstants.HOME,
        timeoutMillis: Long = 30_000,
        onResult: (CommandResult) -> Unit,
    ) {
        val state = TermuxStatus.check(context)
        if (!state.usable) {
            onResult(
                CommandResult(
                    exitCode = null,
                    stdout = "",
                    stderr = "",
                    errmsg = state.detail,
                ),
            )
            return
        }

        val requestId = "req-${requestCodes.incrementAndGet()}"
        val timeout = Runnable {
            finish(
                requestId,
                CommandResult(
                    exitCode = null,
                    stdout = "",
                    stderr = "",
                    errmsg = "Termux did not answer within ${timeoutMillis}ms.",
                    timedOut = true,
                ),
            )
        }

        waiting[requestId] = { result ->
            main.removeCallbacks(timeout)
            onResult(result)
        }
        main.postDelayed(timeout, timeoutMillis)

        val callback = Intent(context, TermuxResultReceiver::class.java).apply {
            putExtra(TermuxResultReceiver.EXTRA_REQUEST_ID, requestId)
        }
        // MUTABLE is required from Android 12: Termux has to be able to add
        // the result extras to this intent. An immutable one silently
        // produces an empty result.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pending = PendingIntent.getBroadcast(
            context,
            requestCodes.incrementAndGet(),
            callback,
            flags,
        )

        val intent = Intent().apply {
            setClassName(TermuxConstants.PACKAGE, TermuxConstants.RUN_COMMAND_SERVICE)
            action = TermuxConstants.ACTION_RUN_COMMAND
            putExtra(TermuxConstants.EXTRA_COMMAND_PATH, executable)
            putExtra(TermuxConstants.EXTRA_ARGUMENTS, arguments.toTypedArray())
            putExtra(TermuxConstants.EXTRA_WORKDIR, workingDirectory)
            // Background: no terminal session is opened and the owner is
            // not pulled out of whatever they were doing.
            putExtra(TermuxConstants.EXTRA_BACKGROUND, true)
            putExtra(TermuxConstants.EXTRA_SESSION_ACTION, "0")
            putExtra(TermuxConstants.EXTRA_PENDING_INTENT, pending)
        }

        try {
            // Must be a foreground service start: Termux calls
            // startForeground, and a plain startService is refused while
            // this app is in the background.
            ContextCompat.startForegroundService(context, intent)
            Log.i(TAG, "sent $requestId: $executable ${arguments.joinToString(" ")}")
        } catch (failure: Exception) {
            Log.w(TAG, "could not start RunCommandService", failure)
            finish(
                requestId,
                CommandResult(
                    exitCode = null,
                    stdout = "",
                    stderr = "",
                    errmsg = "Could not start Termux: ${failure.message}",
                ),
            )
        }
    }

    /** Called by the result receiver. */
    internal fun deliver(requestId: String, result: CommandResult) {
        main.post { finish(requestId, result) }
    }

    private fun finish(requestId: String, result: CommandResult) {
        // remove() returning null means this request already completed, so
        // a late result after a timeout cannot call back twice.
        val callback = waiting.remove(requestId) ?: return
        callback(result)
    }
}
