package com.androidharness.permissions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.androidharness.HarnessApplication
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Asking for a runtime permission from a tool.
 *
 * The awkward part is not the dialog, it is *where the asking happens
 * from*. A tool runs on a worker thread with no activity, and since
 * Android 10 an app with no window on screen cannot start one. So there
 * are three outcomes, not two, and conflating them is how an agent ends
 * up claiming it has no location when really it never asked:
 *
 *  - **granted** - go ahead
 *  - **refused** - the owner said no, and saying no must stick
 *  - **cannot ask** - nobody is looking; report that plainly and let
 *    the owner grant it next time they open the app
 *
 * The third case is the one that matters for scheduled runs, and it is
 * the reason this returns a named outcome rather than a boolean.
 */
object Permissions {

    enum class Outcome { GRANTED, REFUSED, CANNOT_ASK, TIMED_OUT }

    fun granted(context: Context, permissions: List<String>): Boolean =
        permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Ensure every permission is held, prompting if there is a window
     * on screen to prompt from.
     */
    fun ensure(context: Context, permissions: List<String>, timeoutSeconds: Long = 60): Outcome {
        if (granted(context, permissions)) return Outcome.GRANTED
        if (!HarnessApplication.isForeground) return Outcome.CANNOT_ASK

        val latch = CountDownLatch(1)
        var answer = Outcome.TIMED_OUT
        synchronized(this) {
            pending = { outcome ->
                answer = outcome
                latch.countDown()
            }
        }
        try {
            val intent = Intent(context, PermissionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(PermissionRequestActivity.EXTRA_PERMISSIONS, permissions.toTypedArray())
            context.startActivity(intent)
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) return Outcome.TIMED_OUT
            return answer
        } finally {
            synchronized(this) { pending = null }
        }
    }

    /** A human sentence for an outcome, for the model to pass on. */
    fun explain(outcome: Outcome, what: String): String = when (outcome) {
        Outcome.GRANTED -> ""
        Outcome.REFUSED ->
            "The owner declined the $what permission. Do not ask again in this " +
                "conversation; it can be granted under App info > Permissions."
        Outcome.CANNOT_ASK ->
            "The $what permission has not been granted, and Harness is not on " +
                "screen, so Android will not let it ask. Open Harness and try " +
                "again, or grant it under App info > Permissions."
        Outcome.TIMED_OUT -> "The $what permission prompt was not answered."
    }

    fun code(outcome: Outcome): String = when (outcome) {
        Outcome.GRANTED -> "ok"
        Outcome.REFUSED -> "permission_refused"
        Outcome.CANNOT_ASK -> "permission_cannot_ask"
        Outcome.TIMED_OUT -> "permission_no_answer"
    }

    @Volatile
    internal var pending: ((Outcome) -> Unit)? = null

    internal fun deliver(outcome: Outcome) {
        synchronized(this) { pending }?.invoke(outcome)
    }
}
