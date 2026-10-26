package com.androidharness.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.androidharness.core.AgentCore
import com.androidharness.core.AgentHost
import com.androidharness.core.ProviderSettings
import com.androidharness.sandbox.SandboxLimits
import com.androidharness.sandbox.SandboxOutcome
import com.androidharness.sandbox.SandboxRequest
import com.androidharness.sandbox.SharedStorage
import com.androidharness.sandbox.TermuxSandbox
import com.androidharness.termux.CommandResult
import com.androidharness.termux.TermuxBridge
import com.androidharness.termux.TermuxConstants
import com.androidharness.termux.TermuxReadiness
import com.androidharness.termux.TermuxStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

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
    private lateinit var storageView: TextView
    private lateinit var baseUrlField: EditText
    private lateinit var modelField: EditText
    private lateinit var apiKeyField: EditText
    private lateinit var askField: EditText
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

        storageView = body("")
        root.addView(storageView)
        root.addView(
            Button(this).apply {
                text = "Grant all-files access"
                setOnClickListener { requestAllFilesAccess() }
            },
        )
        root.addView(
            Button(this).apply {
                text = "Write a file in the sandbox and read it back"
                setOnClickListener { runFileRoundTrip() }
            },
        )
        root.addView(
            Button(this).apply {
                text = "Time the sandbox"
                setOnClickListener { runBenchmark() }
            },
        )

        root.addView(heading("Agent core"))
        root.addView(
            body(
                "A second Python, embedded in this app rather than in Termux. " +
                    "It runs the agent itself, never model-written code - that " +
                    "stays on the far side of the UID boundary.",
            ),
        )
        root.addView(
            Button(this).apply {
                text = "Start the core and ask it to report"
                setOnClickListener { runCoreProbe() }
            },
        )
        root.addView(
            Button(this).apply {
                text = "Check the approval store"
                setOnClickListener { runApprovalCheck() }
            },
        )

        root.addView(heading("Model provider"))
        root.addView(
            body(
                "Any OpenAI-compatible endpoint: Gemini, OpenAI, OpenRouter, " +
                    "Groq, or a local llama.cpp, Ollama or vLLM server. Fetch " +
                    "the list rather than typing a model name - a wrong name " +
                    "comes back as an unhelpful 404.",
            ),
        )
        val saved = ProviderSettings.load(this)
        baseUrlField = field("Base URL", saved.baseUrl)
        modelField = field("Model name", saved.model)
        apiKeyField = field("API key", saved.apiKey)
        root.addView(baseUrlField)
        root.addView(modelField)
        root.addView(apiKeyField)
        root.addView(
            Button(this).apply {
                text = "Save"
                setOnClickListener { saveProvider() }
            },
        )
        root.addView(
            Button(this).apply {
                text = "Fetch the model list"
                setOnClickListener { fetchModels() }
            },
        )
        root.addView(
            Button(this).apply {
                text = "Test the model"
                setOnClickListener { testModel() }
            },
        )

        root.addView(heading("Ask the agent"))
        root.addView(
            body(
                "The whole thing joined up: the model proposes, the policy " +
                    "decides, and the tools run here. Try \"what is my battery " +
                    "level?\" or \"use python to work out the 200th Fibonacci " +
                    "number\".",
            ),
        )
        askField = field("Ask something", "")
        root.addView(askField)
        root.addView(
            Button(this).apply {
                text = "Send"
                setOnClickListener { ask() }
            },
        )
        root.addView(
            Button(this).apply {
                text = "New conversation"
                setOnClickListener { resetConversation() }
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
        storageView.text = if (SharedStorage.readable()) {
            "All-files access: granted. Files written by the sandbox can be read back."
        } else {
            "All-files access: not granted. The sandbox can run, but files it " +
                "produces cannot be read back."
        }
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

    private fun runFileRoundTrip() {
        val script = """
            # A PDF is the real target, but a plain file proves the path
            # without pulling in a library. The script writes into its own
            # workspace; it cannot see shared storage at all. The wrapper
            # copies the declared file out afterwards.
            with open("report.txt", "w") as handle:
                handle.write("written inside the sandbox\n")
                for index in range(5):
                    handle.write(f"line {index}\n")
            print("script wrote report.txt")
        """.trimIndent()

        outputView.text = "Running in the sandbox…"
        worker.execute {
            val outcome = sandbox.run(
                SandboxRequest(
                    script = script,
                    interpreter = "python",
                    expectedOutputs = listOf("report.txt"),
                ),
                SandboxLimits(),
            )
            runOnUiThread { showSandbox(outcome) }
        }
    }

    /**
     * Measures where a run's time actually goes, before anything is built
     * to make it faster.
     *
     * Three layers are timed, each a superset of the one above, so the
     * differences isolate the costs:
     *
     *  - **echo**: one Termux round trip and nothing else. This is the
     *    floor - starting the service and getting a PendingIntent back.
     *  - **sh**: the full wrapper, proot included, running a shell no-op.
     *  - **python**: the same, running an empty Python script.
     *
     * The gaps say which daemon is worth building. If most of the time is
     * in `echo`, a resident Termux session is the answer. If it is in
     * `python`, the interpreter has to stay warm instead, and keeping the
     * session alive would buy almost nothing.
     */
    private fun runBenchmark() {
        outputView.text = "Timing… this runs fifteen commands and takes a moment."
        worker.execute {
            val echo = measure(REPEATS) { timeEcho() }
            val shell = measure(REPEATS) { timeSandbox("sh") }
            val python = measure(REPEATS) { timeSandbox("python") }

            val report = buildString {
                append("[timing] median of $REPEATS runs, milliseconds\n\n")
                append(line("termux round trip", echo))
                append(line("+ wrapper and proot", shell))
                append(line("+ python startup", python))
                append("\nwhere the time goes:\n")
                val ipc = echo.median
                val proot = shell.median - echo.median
                val interpreter = python.median - shell.median
                append("  termux ipc:   $ipc ms\n")
                append("  proot:        $proot ms\n")
                append("  interpreter:  $interpreter ms\n")
                append("  total:        ${python.median} ms\n\n")
                append(verdict(ipc, proot, interpreter))
            }
            runOnUiThread { outputView.text = report }
        }
    }

    private fun verdict(ipc: Long, proot: Long, interpreter: Long): String {
        val biggest = maxOf(ipc, proot, interpreter)
        return when {
            biggest < 150 ->
                "verdict: FAST ENOUGH - a daemon would be premature."
            biggest == ipc ->
                "verdict: TERMUX IPC DOMINATES - a resident session is the fix."
            biggest == interpreter ->
                "verdict: INTERPRETER DOMINATES - a resident session would " +
                    "barely help; python itself has to stay warm."
            else ->
                "verdict: PROOT DOMINATES - the container setup is the cost, " +
                    "not the plumbing around it."
        }
    }

    private fun line(label: String, t: Timing): String =
        "  ${label.padEnd(20)} median ${t.median}  min ${t.min}  max ${t.max}\n"

    private data class Timing(val median: Long, val min: Long, val max: Long)

    /** A median, because one slow scheduling hiccup should not set the number. */
    private fun measure(repeats: Int, once: () -> Long): Timing {
        val samples = (1..repeats).map { once() }.sorted()
        return Timing(samples[samples.size / 2], samples.first(), samples.last())
    }

    private fun timeSandbox(interpreter: String): Long {
        val started = SystemClock.elapsedRealtime()
        // An empty script: this measures the machinery, not the work.
        sandbox.run(
            SandboxRequest(script = "", interpreter = interpreter),
            SandboxLimits(),
        )
        return SystemClock.elapsedRealtime() - started
    }

    private fun timeEcho(): Long {
        val started = SystemClock.elapsedRealtime()
        val finished = CountDownLatch(1)
        TermuxBridge.run(
            context = this,
            executable = "${TermuxConstants.BIN}/echo",
            arguments = listOf("x"),
        ) { finished.countDown() }
        finished.await(30, TimeUnit.SECONDS)
        return SystemClock.elapsedRealtime() - started
    }

    private fun runCoreProbe() {
        outputView.text = "Starting the embedded interpreter…"
        worker.execute {
            val report = AgentCore.probe(this)
            val text = buildString {
                append("[core]\n")
                append("interpreter start: ${report.startupMillis} ms")
                append(if (report.startupMillis < 0) " (already running)\n" else "\n")
                append("probe call:        ${report.callMillis} ms\n\n")
                append(report.text)
                append("\n\nverdict: ")
                append(
                    if (report.text.contains("sqlite") && report.text.contains("writable")) {
                        "CORE RUNS - the stdlib and a writable store are both there."
                    } else {
                        "CORE INCOMPLETE - read the fields above."
                    },
                )
            }
            runOnUiThread { outputView.text = text }
        }
    }

    private fun runApprovalCheck() {
        outputView.text = "Exercising the approval store…"
        worker.execute {
            val report = AgentCore.selfCheckApprovals(this)
            runOnUiThread { outputView.text = "[approvals]\n" + report }
        }
    }

    private fun field(label: String, initial: String): EditText =
        EditText(this).apply {
            hint = label
            setText(initial)
            setSingleLine()
            // The key is a secret the owner pastes in; it should not be
            // offered to a keyboard that learns what it is told.
            inputType = if (label == "API key") {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        }

    private fun currentProvider(): ProviderSettings.Config =
        ProviderSettings.Config(
            baseUrl = baseUrlField.text.toString().trim(),
            model = modelField.text.toString().trim(),
            apiKey = apiKeyField.text.toString().trim(),
        )

    private fun saveProvider() {
        val config = currentProvider()
        ProviderSettings.save(this, config.baseUrl, config.model, config.apiKey)
        outputView.text = "[provider] saved\n\n" + ProviderSettings.load(this).describe()
    }

    private fun fetchModels() {
        val config = currentProvider()
        outputView.text = "Asking ${config.baseUrl} what it serves…"
        worker.execute {
            val found = AgentCore.listModels(this, config.baseUrl, config.apiKey)
            runOnUiThread {
                found.fold(
                    onSuccess = { names ->
                        if (names.isEmpty()) {
                            outputView.text = "[provider] the endpoint listed no models."
                        } else {
                            showModelPicker(names)
                        }
                    },
                    onFailure = { error ->
                        outputView.text = "[provider] could not fetch the list\n\n" +
                            error.message.orEmpty()
                    },
                )
            }
        }
    }

    private fun showModelPicker(names: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("${names.size} models available")
            .setItems(names.toTypedArray()) { _, index ->
                modelField.setText(names[index])
                saveProvider()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun testModel() {
        val config = currentProvider()
        if (!config.usable) {
            outputView.text = "[provider] set a base URL and a model name first."
            return
        }
        outputView.text = "Sending one request to ${config.model}…"
        worker.execute {
            val reply = AgentCore.testModel(this, config.baseUrl, config.model, config.apiKey)
            runOnUiThread {
                outputView.text = "[provider]\n" + reply.fold(
                    onSuccess = { it },
                    onFailure = { "failed: " + it.message.orEmpty() },
                )
            }
        }
    }

    private fun ask() {
        val question = askField.text.toString().trim()
        if (question.isEmpty()) {
            outputView.text = "[agent] type a question first."
            return
        }
        outputView.text = "Thinking…"
        worker.execute {
            val configured = AgentHost.configure(this)
            if (configured.isFailure) {
                runOnUiThread {
                    outputView.text = "[agent] " + configured.exceptionOrNull()?.message.orEmpty()
                }
                return@execute
            }
            val reply = try {
                AgentHost.ask(this, question)
            } catch (failure: Exception) {
                """{"status":"failed","text":"${failure.message.orEmpty().take(300)}"}"""
            }
            runOnUiThread { outputView.text = renderAnswer(reply, configured.getOrDefault(listOf())) }
        }
    }

    /**
     * Shows the decision, not just the sentence.
     *
     * The point of the architecture is that an answer carries how it
     * was arrived at: which tools ran, what is waiting on approval, and
     * whether the run has read anything a stranger wrote.
     */
    private fun renderAnswer(json: String, tools: List<String>): String = buildString {
        val parsed = runCatching { JSONObject(json) }.getOrNull()
            ?: return "[agent] unreadable reply:\n$json"
        append("[agent] status: ").append(parsed.optString("status")).append('\n')
        append("tools available: ").append(tools.joinToString(", ")).append("\n\n")
        append(parsed.optString("text").ifBlank { "(no text)" }).append('\n')

        val used = parsed.optJSONArray("tools_used")
        if (used != null && used.length() > 0) {
            append("\nwhat ran:\n")
            for (index in 0 until used.length()) {
                val item = used.getJSONObject(index)
                append("  ").append(item.optString("status")).append(" - ")
                append(item.optString("summary").take(300)).append('\n')
            }
        }
        val waiting = parsed.optJSONArray("pending_approvals")
        if (waiting != null && waiting.length() > 0) {
            append("\nwaiting for you to approve:\n")
            for (index in 0 until waiting.length()) {
                val item = waiting.getJSONObject(index)
                append("  ").append(item.optString("tool")).append(" - ")
                append(item.optString("reason")).append('\n')
            }
        }
        if (parsed.optBoolean("tainted")) {
            append("\nthis run read content written by someone else, so further ")
            append("access to private data will ask first.\n")
        }
        parsed.optString("error").takeIf { it.isNotBlank() }?.let {
            append("\nerror: ").append(it).append('\n')
        }
    }

    private fun resetConversation() {
        worker.execute {
            val had = runCatching { AgentHost.reset(this) }.getOrDefault(false)
            runOnUiThread {
                outputView.text = if (had) {
                    "[agent] started a new conversation; the old context is gone."
                } else {
                    "[agent] there was no conversation to clear."
                }
            }
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
                REQUEST_STORAGE,
            )
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", packageName, null),
            ),
        )
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
            if (outcome.outputs.isNotEmpty()) {
                outcome.outputs.forEach { (name, bytes) ->
                    append("file: $name (${bytes.size} bytes)\n")
                    append(bytes.decodeToString().prependIndent("  "))
                    append("\n")
                }
            }
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
        const val REQUEST_STORAGE = 2

        /** Enough to median away a hiccup, few enough to stay bearable. */
        const val REPEATS = 5
    }
}
