package com.androidharness.permissions

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.ActivityCompat

/**
 * An activity with no interface whose only purpose is to be a window,
 * because Android will only show a permission dialog to one.
 *
 * It appears, asks, reports the answer back to the waiting tool, and
 * finishes. The owner sees the system dialog and nothing else.
 */
class PermissionRequestActivity : Activity() {

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val wanted = intent.getStringArrayExtra(EXTRA_PERMISSIONS)
        if (wanted == null || wanted.isEmpty()) {
            Permissions.deliver(Permissions.Outcome.GRANTED)
            finish()
            return
        }
        // A recreated activity (a rotation during the dialog) must not
        // stack a second prompt on top of the first.
        if (state == null) ActivityCompat.requestPermissions(this, wanted, REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        results: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        val allowed = results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }
        Permissions.deliver(
            if (allowed) Permissions.Outcome.GRANTED else Permissions.Outcome.REFUSED,
        )
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_PERMISSIONS = "permissions"
        private const val REQUEST = 7001
    }
}
