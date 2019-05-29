/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
@file:Suppress("MatchingDeclarationName")

package mozilla.telemetry.glean.error

import androidx.annotation.VisibleForTesting
import mozilla.telemetry.glean.Dispatchers
import mozilla.telemetry.glean.private.CounterMetricType
import mozilla.telemetry.glean.private.Lifetime

// Test-only internal object.
//
// This allows to check the number of errors recorded per metric.

@VisibleForTesting(otherwise = VisibleForTesting.NONE)
object ErrorRecording {
    // Must match the ErrorType in `glean-core/src/error_recording.rs`
    internal enum class ErrorType {
        InvalidValue,
        InvalidLabel
    }

    // Must match the string representation of the ErrorType in `glean-core/src/error_recording.rs`
    @Suppress("TopLevelPropertyNaming")
    internal val GLEAN_ERROR_NAMES = mapOf(
        ErrorType.InvalidValue to "invalid_value",
        ErrorType.InvalidLabel to "invalid_label"
    )

    /**
    * Get the number of recorded errors for the given metric and error type.
    *
    * @param identifier The full metric identifier
    * @param errorType The type of error
    * @param pingName The name of the ping.
    * @return The number of errors reported
    */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    internal fun testGetNumRecordedErrors(
        identifier: String,
        errorType: ErrorType,
        pingName: String
    ): Int {
        @Suppress("EXPERIMENTAL_API_USAGE")
        Dispatchers.API.assertInTestingMode()

        val errorName = GLEAN_ERROR_NAMES[errorType]!!

        val errorMetric = CounterMetricType(
            disabled = false,
            category = "glean.error",
            lifetime = Lifetime.Ping,
            name = "$errorName/$identifier",
            sendInPings = listOf(pingName)
        )

        return errorMetric.testGetValue(pingName)
    }
}
