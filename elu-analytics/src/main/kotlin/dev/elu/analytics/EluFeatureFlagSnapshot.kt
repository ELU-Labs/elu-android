package dev.elu.analytics

import dev.elu.analytics.internal.flags.FlagJson
import dev.elu.analytics.internal.flags.FlagJsonValue
import dev.elu.analytics.internal.flags.FlagResponse
import java.util.Collections
import java.util.Date

/** A detached complete publication. Keeping this value does not keep its authority current. */
public class EluFeatureFlagSnapshot internal constructor(
    response: FlagResponse?,
    public val source: Source,
    public val error: LoadError?,
) {
    public enum class Source { REMOTE, CACHE, UNAVAILABLE }
    public enum class LoadError { TRANSPORT, INVALID_RESPONSE }

    /** Java and Kotlin callers receive the same main-thread callback. */
    public fun interface Listener {
        public fun onFlags(snapshot: EluFeatureFlagSnapshot)
    }

    public sealed class Value {
        public data class BooleanValue(public val value: Boolean) : Value()
        public data class StringValue(public val value: String) : Value()
        public data class NumberValue(public val value: Double) : Value()
        public data object NullValue : Value()
    }

    public class Entry internal constructor(
        public val key: String,
        public val value: Value,
        payload: ByteArray?,
    ) {
        private val payload = payload?.copyOf()
        /** Null means absent; the bytes `null` mean an evaluated JSON null payload. */
        public val payloadJSON: ByteArray? get() = payload?.copyOf()
    }

    private val flagsBytes = response?.let { FlagJson.canonicalBytes(it.flags) } ?: "{}".toByteArray(Charsets.UTF_8)
    private val payloadBytes = response?.let { FlagJson.canonicalBytes(it.payloads) } ?: "{}".toByteArray(Charsets.UTF_8)
    private val evaluated = response?.evaluatedAt?.toEpochMillisFloor()
    private val expires = response?.expiresAt?.toEpochMillisFloor()
    public val entries: List<Entry> = Collections.unmodifiableList(response?.let { original -> original.flags.members.map { member ->
        val value = when (val flag = member.value) {
            is FlagJsonValue.BooleanValue -> Value.BooleanValue(flag.value)
            is FlagJsonValue.StringValue -> Value.StringValue(flag.value)
            is FlagJsonValue.NumberValue -> Value.NumberValue(flag.value)
            FlagJsonValue.NullValue -> Value.NullValue
            else -> kotlin.error("Validated flag value is not scalar")
        }
        Entry(member.key, value, original.payloads.member(member.key)?.let(FlagJson::canonicalBytes))
    } } ?: emptyList())
    public val flagsJSON: ByteArray get() = flagsBytes.copyOf()
    public val payloadsJSON: ByteArray get() = payloadBytes.copyOf()
    public val requestId: String? = response?.requestId
    public val flagsRevision: String? = response?.flagsRevision
    public val evaluatedAt: Date? get() = evaluated?.let(::Date)
    /** Original response expiry; configuration or identity may invalidate this value earlier. */
    public val expiresAt: Date? get() = expires?.let(::Date)
    public val isAvailable: Boolean get() = source != Source.UNAVAILABLE

    /** Exact decoded UTF-16 lookup; canonically equivalent keys remain distinct. */
    public fun getEntry(key: String): Entry? = entries.firstOrNull { it.key == key }
}
