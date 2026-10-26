package com.androidharness.tools

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import com.androidharness.permissions.Permissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Where the phone is.
 *
 * Uses the platform LocationManager rather than Play Services, so the
 * app keeps working on a device without Google's stack - the same
 * reasoning that put Termux behind an interface.
 *
 * A cached fix is offered when it is recent, because waiting thirty
 * seconds for GPS to answer a question the phone already knows is its
 * own kind of wrong. Its age is always stated.
 */
class LocationTool(private val context: Context) : NativeTool {

    override val name = "get_location"
    override val description =
        "Find where the phone is now, as latitude and longitude with an accuracy " +
            "and the age of the reading."
    override val inputSchema = """
        {
          "type": "object",
          "properties": {
            "accept_cached_seconds": {"type": "integer", "minimum": 0, "maximum": 86400}
          },
          "required": []
        }
    """.trimIndent()
    override val risk = "sensitive_read"
    override val timeoutSeconds = 60.0

    private val permissions = listOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    override fun run(payloadJson: String): String {
        val arguments = NativeTool.argumentsOf(payloadJson)
        val acceptableAge = arguments.optLong("accept_cached_seconds", 120L).coerceIn(0L, 86_400L)

        val outcome = Permissions.ensure(context, permissions)
        if (outcome != Permissions.Outcome.GRANTED) {
            return NativeTool.error(
                Permissions.explain(outcome, "location"),
                Permissions.code(outcome),
                retryable = outcome == Permissions.Outcome.CANNOT_ASK,
            )
        }

        val manager = context.getSystemService(LocationManager::class.java)
            ?: return NativeTool.error("no location service", "location_unavailable")

        val cached = freshest(manager)
        if (cached != null && ageSeconds(cached) <= acceptableAge) return report(cached)

        val fresh = requestOne(manager)
            ?: cached
            ?: return NativeTool.error(
                "No location came back. Check that Location is switched on in " +
                    "quick settings; indoors the first fix can take a while.",
                "location_unavailable",
                retryable = true,
            )
        return report(fresh)
    }

    private fun freshest(manager: LocationManager): Location? =
        runCatching {
            manager.getProviders(true)
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        }.getOrNull()

    private fun requestOne(manager: LocationManager): Location? {
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Older releases have no single-shot call that is safe to
            // wait on from a worker thread; the cached fix stands.
            return null
        }
        val latch = CountDownLatch(1)
        var found: Location? = null
        val executor = Executors.newSingleThreadExecutor()
        return try {
            manager.getCurrentLocation(provider, CancellationSignal(), executor) {
                found = it
                latch.countDown()
            }
            latch.await(30, TimeUnit.SECONDS)
            found
        } catch (error: SecurityException) {
            null
        } finally {
            executor.shutdownNow()
        }
    }

    private fun ageSeconds(location: Location): Long =
        ((System.currentTimeMillis() - location.time) / 1000).coerceAtLeast(0)

    private fun report(location: Location): String {
        val place = ToolFormatting.Place(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMetres = location.accuracy,
            ageSeconds = ageSeconds(location),
            provider = location.provider ?: "unknown",
        )
        return NativeTool.ok(
            ToolFormatting.describeLocation(place),
            JSONObject()
                .put("latitude", place.latitude)
                .put("longitude", place.longitude)
                .put("accuracy_metres", place.accuracyMetres)
                .put("age_seconds", place.ageSeconds)
                .put("provider", place.provider),
        )
    }
}
