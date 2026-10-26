package com.androidharness.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.androidharness.termux.CommandResult
import com.androidharness.termux.TermuxBridge
import com.androidharness.termux.TermuxConstants
import com.androidharness.termux.TermuxReadiness
import com.androidharness.sandbox.SandboxLimits
import com.androidharness.sandbox.SandboxOutcome
import com.androidharness.sandbox.SandboxRequest
import com.androidharness.sandbox.TermuxSandbox
import com.androidharness.termux.TermuxStatus
import java.util.concurrent.Executors

/**
 * Phase 0: prove the round trip.
 *
 * The only question this screen answers is whether this app can get a
 * command run inside Termux and read the output back. Everything else in
 * the plan depends on that, so it is worth a screen of its own rather
 * than a log line.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var remedyView: TextView
    private lateinit var outputView: TextView
    private lateinit var permissionButton: Button
    private val sandbox by lazy { TermuxSandbox(applicationContext) }
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(heading("Termux connection"))
        root.addView(
            body(
                "The harness runs model-written code inside Termux, where " +
                    "it is isolated by proot and runs under a different " +
                    "Android user than this app. Everything else works " +
                    "without Termux.",
            ),
        )

        statusView = body("")
        root.addView(statusView)
        remedyView = body("")
        root.addView(remedyView)

        permissionButton = Button(this).apply {
            text = "Grant permission"
            setOnClickListener { requestRunCommandPermission() }
        }
        root.addView(permissionButton)

        root.addView(
            Button(this).apply {
                text = "Open this app's settings"
                setOnClickListener {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null),
                        ),
                    )
                }
            },
        )

        root.addView(heading("Round trip"))
        root.addView(
            body(
                "Runs `echo` inside Termux and shows exactly what comes " +
                    "back. If this works, the architecture holds.",
            ),
        )
        root.addView(
            Button(this).apply {
                text = "Run echo in Termux"
                setOnClickListener { runEcho() }
            },
        )
        root.addView(
            Button(this).apply {
                text = "Report the Termux environment"
                setOnClickListener { runEnvironmentReport() }
            },
        )

        root.addView(heading("Sandbox"))
        root.addView(
            body(
                "Runs Python under proot. The isolation check is the real " +
                    "test: the script tries to reach the owner's files and " +
                    "Termux's own home, and must find nothing.",
            ),
        )
        root.addView(
            Button(this).apply {
                text = "Run Python in the sandbox"
                setOnClickListener { runSandboxHello() }
            },
        )
        root.addView(
            Button(this).apply {
                text = "Prove the isolation"
                setOnClickListener { runIsolationCheck() }
            },
        )

        outputView = body("Nothing run yet.").apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(outputView)

        setContentView(
            ScrollView(this).apply {
                addView(
                    root,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            },
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val state = TermuxStatus.check(this)
        statusView.text = "Status: ${state.detail}"
        remedyView.text = state.remedy
        permissionButton.isEnabled =
            state.readiness == TermuxReadiness.PERMISSION_NOT_GRANTED
    }

    private fun requestRunCommandPermission() {
        // On many builds this permission is not offered through the normal
        // runtime dialog and has to be granted from the app's permission
        // page, so the request is a convenience rather than the answer.
        ActivityCompat.requestPermissions(
            this,
            arrayOf(TermuxConstants.PERMISSION_RUN_COMMAND),
            REQUEST_RUN_COMMAND,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun runEcho() {
        outputView.text = "Running…"
        TermuxBridge.run(
            context = this,
            executable = "${TermuxConstants.BIN}/echo",
            arguments = listOf("round trip ok"),
        ) { result -> show("echo", result) }
    }

    private fun runEnvironmentReport() {
        outputView.text = "Running…"
        // Answers the questions Phase 1 depends on: is there a Python, and
        // is proot installed for the sandbox.
        val script = "echo \"python: \$(command -v python || echo missing)\"; " +
            "echo \"proot: \$(command -v proot || echo missing)\"; " +
            "echo \"uname: \$(uname -m)\""
        TermuxBridge.run(
            context = this,
            executable = "${TermuxConstants.BIN}/sh",
            arguments = listOf("-c", script),
        ) { result -> show("environment", result) }
    }

    private fun runSandboxHello() {
        val script = """
            import sys, os
            print("python", sys.version.split()[0])
            print("cwd", os.getcwd())
            print("sandbox hello")
        """.trimIndent()
        runInSandbox("python", script)
    }

    private fun runIsolationCheck() {
        // Each line is a thing the script must NOT be able to see. The
        // owner's media, and Termux's home, which is where the predecessor
        // kept its API keys.
        val script = """
            import os

            def report(label, path):
                try:
                    entries = os.listdir(path)
                except Exception as error:
                    print(f"{label}: blocked ({type(error).__name__})")
                    return
                if entries:
                    print(f"{label}: VISIBLE - {len(entries)} entries - LEAK")
                else:
                    print(f"{label}: empty")

            report("home", os.path.expanduser("~"))
            report("sdcard", "/sdcard")
            report("storage", "/storage")
            print("workspace writable:", os.access(os.getcwd(), os.W_OK))
        """.trimIndent()
        runInSandbox("python", script)
    }

    private fun runInSandbox(interpreter: String, script: String) {
        outputView.text = "Running in the sandbox…"
        // The sandbox call blocks, and the result is delivered on the main
        // thread, so running it there would deadlock until the timeout.
        worker.execute {
            val outcome = sandbox.run(
                SandboxRequest(script = script, interpreter = interpreter),
                SandboxLimits(),
            )
            runOnUiThread { showSandbox(outcome) }
        }
    }

    private fun showSandbox(outcome: SandboxOutcome) {
        outputView.text = buildString {
            append("[sandbox]\n")
            if (outcome.stdout.isNotEmpty()) append("stdout:\n${outcome.stdout}\n")
            if (outcome.stderr.isNotEmpty()) append("stderr:\n${outcome.stderr}\n")
            if (outcome.errorCode.isNotEmpty()) append("error: ${outcome.errorCode}\n")
            if (outcome.remedy.isNotEmpty()) append("remedy: ${outcome.remedy}\n")
            append("\nverdict: ${if (outcome.succeeded) "SANDBOX WORKS" else "failed"}")
        }
    }

    private fun show(label: String, result: CommandResult) {
        val report = buildString {
            append("[$label]\n")
            append("exit: ${result.exitCode ?: "none"}\n")
            if (result.stdout.isNotEmpty()) append("stdout:\n${result.stdout}\n")
            if (result.stderr.isNotEmpty()) append("stderr:\n${result.stderr}\n")
            if (result.errmsg.isNotEmpty()) append("termux error:\n${result.errmsg}\n")
            if (result.blockedByExternalAppsPolicy) {
                append(
                    "\nRemedy: in Termux run\n" +
                        "  echo 'allow-external-apps=true' >> ~/.termux/termux.properties\n" +
                        "  termux-reload-settings\n" +
                        "This permits any app holding the RUN_COMMAND " +
                        "permission to run commands in Termux, so grant that " +
                        "permission sparingly.\n",
                )
            }
            if (result.timedOut) {
                append(
                    "\nRemedy: Termux may need \"Display over other apps\" " +
                        "to be started in the background on Android 10+.\n",
                )
            }
            append("\nverdict: ${if (result.succeeded) "ROUND TRIP WORKS" else "failed"}")
        }
        outputView.text = report
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setPadding(0, (24 * resources.displayMetrics.density).toInt(), 0, 8)
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setPadding(0, 4, 0, 8)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_RUN_COMMAND = 1
    }
}
