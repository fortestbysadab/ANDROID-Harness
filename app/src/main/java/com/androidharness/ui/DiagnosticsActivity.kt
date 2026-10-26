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
import com.androidharness.termux.TermuxStatus

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

    private companion object {
        const val REQUEST_RUN_COMMAND = 1
    }
}
