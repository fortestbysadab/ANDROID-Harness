package com.androidharness.sandbox

import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * The one place both sides can see.
 *
 * Termux and this app are different Android users, so neither can read
 * the other's private directory. Shared storage is the handoff, and it
 * is also where the owner wants finished documents anyway - a generated
 * PDF is useless if it is only visible inside an app.
 *
 * Reading non-media files another app wrote needs all-files access.
 * That is a broad permission and is requested honestly rather than
 * worked around: the alternatives are a folder chosen through the
 * document picker, which cannot be mapped to the POSIX path Termux
 * writes to, or chunking the bytes back through stdout, which is bounded
 * by Binder's transaction limit and cannot carry a document.
 */
object SharedStorage {

    private const val TAG = "SharedStorage"
    private const val MAX_OUTPUT_BYTES = 64L * 1024 * 1024

    const val REMEDY =
        "Grant all-files access: Settings > Apps > Android Harness > " +
            "Permissions, or the button on the diagnostics screen. It is " +
            "needed to read files Termux wrote into shared storage."

    fun readable(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // Below Android 11 the ordinary storage permission is enough,
            // and is requested at runtime like any other.
            Environment.getExternalStorageDirectory().canRead()
        }

    /**
     * Read one file the wrapper copied out.
     *
     * Both halves of the path are checked even though the wrapper built
     * them: the directory came back over an IPC boundary and the name
     * originated with the model, so neither is trusted here.
     */
    fun read(directory: String, name: String): ByteArray? {
        if (!OutputNames.isSafe(name)) return null
        if (!directory.startsWith(SandboxRunner.SHARED_ROOT)) {
            Log.w(TAG, "refusing a directory outside the run root: $directory")
            return null
        }
        val file = File(directory, name)
        // kotlin.io's startsWith compares path components, so it cannot
        // be fooled by a sibling directory with a shared prefix.
        val canonicalRoot = File(SandboxRunner.SHARED_ROOT).canonicalFile
        if (!file.canonicalFile.startsWith(canonicalRoot)) {
            Log.w(TAG, "refusing a path that escapes the run root")
            return null
        }
        if (!file.isFile) return null
        if (file.length() > MAX_OUTPUT_BYTES) {
            Log.w(TAG, "refusing ${file.length()} bytes from $name")
            return null
        }
        return try {
            file.readBytes()
        } catch (failure: Exception) {
            Log.w(TAG, "could not read $name", failure)
            null
        }
    }
}
