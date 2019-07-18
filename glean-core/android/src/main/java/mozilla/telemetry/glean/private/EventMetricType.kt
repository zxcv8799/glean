/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
* file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.telemetry.glean.private

// import android.os.SystemClock
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.sun.jna.StringArray
import mozilla.telemetry.glean.Dispatchers
import mozilla.telemetry.glean.Glean
import mozilla.telemetry.glean.rust.LibGleanFFI
import mozilla.telemetry.glean.rust.getAndConsumeRustString
import mozilla.telemetry.glean.rust.toBoolean
import mozilla.telemetry.glean.rust.toByte
import org.json.JSONArray
import org.json.JSONObject

// import mozilla.components.service.glean.Dispatchers
// import mozilla.components.service.glean.storages.EventsStorageEngine
// import mozilla.components.service.glean.storages.RecordedEventData
// import mozilla.components.support.base.log.logger.Logger

@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
data class RecordedEventData(
    val category: String,
    val name: String,
    var timestamp: Long,
    val extra: Map<String, String>? = null,

    internal val identifier: String = if (category.isEmpty()) { name } else { "$category.$name" }
)

/**
 * An enum with no values for convenient use as the default set of extra keys
 * that an [EventMetricType] can accept.
 */
@Suppress("EmptyClassBlock")
enum class NoExtraKeys(val value: Int) {
    // deliberately empty
}

/**
 * This implements the developer facing API for recording events.
 *
 * Instances of this class type are automatically generated by the parsers at built time,
 * allowing developers to record events that were previously registered in the metrics.yaml file.
 *
 * The Events API only exposes the [record] method, which takes care of validating the input
 * data and making sure that limits are enforced.
 */
class EventMetricType<ExtraKeysEnum : Enum<ExtraKeysEnum>> internal constructor(
    private var handle: Long,
    private val sendInPings: List<String>
) {
    /**
    * The public constructor used by automatically generated metrics.
    */
    constructor(
        disabled: Boolean,
        category: String,
        lifetime: Lifetime,
        name: String,
        sendInPings: List<String>,
        allowedExtraKeys: List<String>? = null
    ) : this(handle = 0, sendInPings = sendInPings) {
        val ffiPingsList = StringArray(sendInPings.toTypedArray(), "utf-8")
        val ffiAllowedExtraKeys = allowedExtraKeys?.let {
            StringArray(it.toTypedArray(), "utf-8")
        }
        val ffiAllowedExtraKeysLen = allowedExtraKeys?.let { it.size } ?: 0
        this.handle = LibGleanFFI.INSTANCE.glean_new_event_metric(
            category = category,
            name = name,
            send_in_pings = ffiPingsList,
            send_in_pings_len = sendInPings.size,
            lifetime = lifetime.ordinal,
            disabled = disabled.toByte(),
            allowed_extra_keys = ffiAllowedExtraKeys,
            allowed_extra_keys_len = ffiAllowedExtraKeysLen
        )
    }

    private fun shouldRecord(): Boolean {
        if (!Glean.isInitialized()) {
            return false
        }

        return LibGleanFFI.INSTANCE.glean_event_should_record(Glean.handle, this.handle).toBoolean()
    }

    /**
     * Record an event by using the information provided by the instance of this class.
     *
     * @param extra optional. This is map, both keys and values need to be strings, keys are
     *              identifiers. This is used for events where additional richer context is needed.
     *              The maximum length for values is defined by [MAX_LENGTH_EXTRA_KEY_VALUE]
     */
    fun record(extra: Map<ExtraKeysEnum, String>? = null) {
        if (!shouldRecord()) {
            return
        }

        // We capture the event time now, since we don't know when the async code below
        // might get executed.
        val timestamp = SystemClock.elapsedRealtime()

        @Suppress("EXPERIMENTAL_API_USAGE")
        Dispatchers.API.launch {
            // The Map is sent over FFI as a pair of arrays, one containing the
            // keys, and the other containing the values.
            // In Kotlin, Map.keys and Map.values are not guaranteed to return the entries
            // in any particular order. Therefore, we iterate over the pairs together and
            // create the keys and values arrays step-by-step.
            var keys: IntArray? = null
            var values: StringArray? = null
            var len: Int = 0
            if (extra != null) {
                val extraList = extra.toList()
                keys = IntArray(extra.size, { extraList[it].first.ordinal })
                values = StringArray(Array<String>(extra.size, { extraList[it].second }), "utf-8")
                len = extra.size
            }

            LibGleanFFI.INSTANCE.glean_event_record(
                Glean.handle,
                this@EventMetricType.handle,
                timestamp,
                keys,
                values,
                len
            )
        }
    }

    /**
     * Tests whether a value is stored for the metric for testing purposes only. This function will
     * attempt to await the last task (if any) writing to the the metric's storage engine before
     * returning a value.
     *
     * @param pingName represents the name of the ping to retrieve the metric for.  Defaults
     *                 to the either the first value in [defaultStorageDestinations] or the first
     *                 value in [sendInPings]
     * @return true if metric value exists, otherwise false
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun testHasValue(pingName: String = sendInPings.first()): Boolean {
        @Suppress("EXPERIMENTAL_API_USAGE")
        Dispatchers.API.assertInTestingMode()

        return LibGleanFFI.INSTANCE.glean_event_test_has_value(
            Glean.handle,
            this.handle,
            pingName
        ).toBoolean()
    }

    /**
     * Deserializes an event in JSON into a RecordedEventData object.
     *
     * @param jsonContent The JSONObject containing the data for the event. It is in
     * the same format as an event sent in a ping, and has the following entries:
     *   - timestamp (Int)
     *   - category (String): The category of the event metric
     *   - name (String): The name of the event metric
     *   - extra (Map<String, String>?): Map of extra key/value pairs
     * @return [RecordedEventData] representing the event data
     */
    private fun deserializeEvent(jsonContent: JSONObject): RecordedEventData {
        val extra: Map<String, String>? = jsonContent.optJSONObject("extra")?.let {
            val extraValues: MutableMap<String, String> = mutableMapOf()
            it.names()?.let { names ->
                for (i in 0 until names.length()) {
                    extraValues[names.getString(i)] = it.getString(names.getString(i))
                }
            }
            extraValues
        }

        return RecordedEventData(
            jsonContent.getString("category"),
            jsonContent.getString("name"),
            jsonContent.getLong("timestamp"),
            extra
        )
    }

    /**
     * Returns the stored value for testing purposes only. This function will attempt to await the
     * last task (if any) writing to the the metric's storage engine before returning a value.
     *
     * @param pingName represents the name of the ping to retrieve the metric for.  Defaults
     *                 to the either the first value in [defaultStorageDestinations] or the first
     *                 value in [sendInPings]
     * @return value of the stored metric
     * @throws [NullPointerException] if no value is stored
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun testGetValue(pingName: String = sendInPings.first()): List<RecordedEventData> {
        @Suppress("EXPERIMENTAL_API_USAGE")
        Dispatchers.API.assertInTestingMode()

        val ptr = LibGleanFFI.INSTANCE.glean_event_test_get_value_as_json_string(
            Glean.handle,
            this.handle,
            pingName
        )!!

        val jsonRes = try {
            JSONArray(ptr.getAndConsumeRustString())
        } catch (e: org.json.JSONException) {
            throw NullPointerException()
        }
        if (jsonRes.length() == 0) {
            throw NullPointerException()
        }

        val result: MutableList<RecordedEventData> = mutableListOf()
        for (i in 0 until jsonRes.length()) {
            result.add(deserializeEvent(jsonRes.getJSONObject(i)))
        }
        return result
    }
}
