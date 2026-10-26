package com.androidharness.core

import android.content.Context
import com.androidharness.sandbox.TermuxSandbox
import com.androidharness.tools.BatteryTool
import com.androidharness.tools.NativeTool
import com.androidharness.tools.SandboxTool
import com.chaquo.python.Kwarg
import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python

/**
 * Builds the agent once and keeps it.
 *
 * The composition root. Everything that knows about both sides - the
 * Python core and the Android capabilities - is here and only here, so
 * that swapping a provider, or adding a tool, is a change in one file.
 *
 * Every method blocks on a model call or a tool. None may run on the
 * main thread.
 */
object AgentHost {

    private const val MODULE = "harness_core.app"

    @Volatile
    private var agent: PyObject? = null

    @Synchronized
    private fun instance(context: Context): PyObject {
        agent?.let { return it }
        val python = Python.getInstance()
        val built = python.getModule(MODULE)
            .callAttr("get_agent", context.filesDir.absolutePath + "/agent")
        registerTools(context, built)
        agent = built
        return built
    }

    /**
     * Hand the core its capabilities.
     *
     * Each tool declares its own risk here, once. Nothing later can
     * raise or lower it - the policy reads the declaration, never the
     * model's description of what it intends.
     */
    private fun registerTools(context: Context, agent: PyObject) {
        val bridge = Python.getInstance().getModule("harness_core.bridge")
        val registry = agent.get("registry")
        val tools: List<NativeTool> = listOf(
            BatteryTool(context.applicationContext),
            SandboxTool(TermuxSandbox(context.applicationContext)),
        )
        tools.forEach { tool ->
            bridge.callAttr(
                "register_native_tool",
                registry,
                Kwarg("name", tool.name),
                Kwarg("description", tool.description),
                Kwarg("input_schema", tool.inputSchema),
                Kwarg("risk", tool.risk),
                // The Kotlin object itself crosses, and Python calls
                // run() on it. Chaquopy proxies the call; no state is
                // shared in either direction.
                Kwarg("handler", tool),
                Kwarg("timeout_seconds", tool.timeoutSeconds),
                Kwarg("version", tool.version),
                Kwarg("returns_untrusted_content", tool.returnsUntrustedContent),
            )
        }
    }

    fun configure(context: Context): Result<List<String>> {
        val settings = ProviderSettings.load(context)
        if (!settings.usable) {
            return Result.failure(IllegalStateException("Set a base URL and model first."))
        }
        return try {
            AgentCore.start(context)
            val built = instance(context)
            built.callAttr(
                "configure_model",
                settings.baseUrl,
                settings.model,
                settings.apiKey.ifBlank { null },
            )
            Result.success(built.callAttr("tool_names").asList().map { it.toString() })
        } catch (failure: PyException) {
            Result.failure(IllegalStateException(AgentCore.explain(failure), failure))
        }
    }

    fun ask(context: Context, text: String, conversationId: String = "device"): String {
        val built = instance(context)
        return built.callAttr("ask", text, conversationId).toString()
    }

    fun reset(context: Context, conversationId: String = "device"): Boolean =
        instance(context).callAttr("reset", conversationId).toBoolean()
}
