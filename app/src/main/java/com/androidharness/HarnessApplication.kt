package com.androidharness

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.androidharness.core.AgentCore

/**
 * Two jobs, both about timing.
 *
 * **Knowing whether anyone is looking.** Since Android 10 an app with no
 * window cannot start an activity, so it cannot show a permission
 * prompt. A tool that needs one must therefore behave differently when
 * the owner is present and when it is three in the morning, and this is
 * the only honest way to tell the difference.
 *
 * **Starting the interpreter early.** CPython takes about 550 ms to come
 * up. That was being paid by whoever pressed the first button. Now it
 * is paid during launch, in the background, while the owner is still
 * reading the screen.
 */
class HarnessApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        registerActivityLifecycleCallbacks(ForegroundWatch)

        // Deliberately not blocking: if it fails, the diagnostics screen
        // reports it properly when something is actually asked of it.
        Thread({ runCatching { AgentCore.start(this) } }, "python-warmup").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    companion object {
        @Volatile
        private var INSTANCE: HarnessApplication? = null

        /** True when one of our activities is resumed, so a prompt can be shown. */
        val isForeground: Boolean get() = ForegroundWatch.resumed > 0
    }

    private object ForegroundWatch : ActivityLifecycleCallbacks {
        @Volatile
        var resumed: Int = 0

        override fun onActivityResumed(activity: Activity) {
            resumed++
        }

        override fun onActivityPaused(activity: Activity) {
            resumed = (resumed - 1).coerceAtLeast(0)
        }

        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
