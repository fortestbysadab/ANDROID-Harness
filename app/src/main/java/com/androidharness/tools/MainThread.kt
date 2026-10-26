package com.androidharness.tools

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs a block on the main thread and waits for its answer.
 *
 * Tools run on the agent's worker thread - they must, since a model
 * call takes seconds - but several platform APIs are only reliable on
 * the main looper, and on some OEM builds the clipboard throws off it.
 * This is the one place that crossing happens, so it is the one place
 * that can deadlock, and the timeout below is why it does not.
 */
object MainThread {

    fun <T> get(timeoutSeconds: Long = 5, block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()

        val latch = CountDownLatch(1)
        var answer: T? = null
        var failure: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try {
                answer = block()
            } catch (error: Throwable) {
                failure = error
            } finally {
                latch.countDown()
            }
        }
        // A timeout returns null rather than hanging the run: a wedged
        // main thread is the owner's problem to see, not a reason for
        // the agent to stop answering.
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) return null
        failure?.let { throw it }
        return answer
    }
}
