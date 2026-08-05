package dev.elu.analytics.internal.core

import java.math.BigDecimal
import java.math.BigInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreStateCodecTest {
    @Test
    fun `canonical identity fixture round trips without changing its closed shape`() {
        val fixture = JSONObject(IDENTITY_FIXTURE)

        val decoded = CoreStateCodec.decodeIdentity(fixture)
        val encoded = CoreStateCodec.encodeIdentity(decoded)

        assertEquals(fixture.keys().asSequence().toSet(), encoded.keys().asSequence().toSet())
        assertEquals(
            fixture.getJSONObject("session").keys().asSequence().toSet(),
            encoded.getJSONObject("session").keys().asSequence().toSet(),
        )
        assertEquals(decoded, CoreStateCodec.decodeIdentity(encoded))
        assertFalse(encoded.has("streamId"))
        assertFalse(encoded.has("nextSequence"))
        assertFalse(encoded.has("identityRevision"))
    }

    @Test
    fun `identity decoder keeps its shape closed with a non-recoverable extension error`() {
        val withUnknown = JSONObject(IDENTITY_FIXTURE).put("streamId", "must-not-be-here")
        val extension =
            assertThrows(UnsupportedCoreSchemaExtensionException::class.java) {
                CoreStateCodec.decodeIdentity(withUnknown)
            }
        assertEquals("identity", extension.recordPath)
        assertEquals(setOf("streamId"), extension.unknownFields)

        val newer =
            JSONObject(IDENTITY_FIXTURE)
                .put("schemaVersion", 2)
                .put("futureIdentityField", true)
        assertThrows(UnsupportedCoreSchemaException::class.java) {
            CoreStateCodec.decodeIdentity(newer)
        }

        val nonCanonicalOffset =
            JSONObject(IDENTITY_FIXTURE).put("updatedAt", "2026-08-04T01:01:00.000+01:00")
        assertThrows(CoreStateCorruptionException::class.java) {
            CoreStateCodec.decodeIdentity(nonCanonicalOffset)
        }
    }

    @Test
    fun `schema major is checked before shape at the aggregate and every versioned child`() {
        listOf<String?>(null, "identity", "stream", "flagContext").forEach { record ->
            val root = validAggregateObject()
            val target = record?.let(root::getJSONObject) ?: root
            target
                .put("schemaVersion", 2)
                .put("futureField", "newer-sdk-data")
            val bytes = root.toString().toByteArray()
            val original = bytes.copyOf()

            val decodeError =
                assertThrows(UnsupportedCoreSchemaException::class.java) {
                    CoreStateCodec.decode(bytes)
                }
            assertEquals(2L, decodeError.foundVersion)
            assertThrows(UnsupportedCoreSchemaException::class.java) {
                CoreStateCodec.recoverableRecords(bytes)
            }
            assertTrue(bytes.contentEquals(original))
        }
    }

    @Test
    fun `unknown same-major fields abort recovery at the aggregate and every versioned child`() {
        listOf<String?>(null, "identity", "stream", "flagContext").forEach { record ->
            val root = validAggregateObject()
            val target = record?.let(root::getJSONObject) ?: root
            target.put("futureField", JSONObject().put("source", "newer-sdk"))
            val bytes = root.toString().toByteArray()
            val original = bytes.copyOf()

            val decodeError =
                assertThrows(UnsupportedCoreSchemaExtensionException::class.java) {
                    CoreStateCodec.decode(bytes)
                }
            assertEquals(record ?: "core state", decodeError.recordPath)
            assertEquals(setOf("futureField"), decodeError.unknownFields)

            val recoveryError =
                assertThrows(UnsupportedCoreSchemaExtensionException::class.java) {
                    CoreStateCodec.recoverableRecords(bytes)
                }
            assertEquals(record ?: "core state", recoveryError.recordPath)
            assertTrue(bytes.contentEquals(original))
        }
    }

    @Test
    fun `damaged aggregate schema marker still recovers independently valid children`() {
        val expected = CoreStateCodec.decode(validAggregateBytes())
        val withoutMarker = validAggregateObject().apply { remove("schemaVersion") }
        val malformedMarker = validAggregateObject().put("schemaVersion", "one")

        listOf(withoutMarker, malformedMarker).forEach { damaged ->
            val recovered = CoreStateCodec.recoverableRecords(damaged.toString().toByteArray())

            assertEquals(expected.identity, recovered.identity)
            assertEquals(expected.stream, recovered.stream)
            assertEquals(expected.flagContext, recovered.flagContext)
        }
    }

    @Test
    fun `damaged aggregate marker still propagates non-recoverable child schemas`() {
        val unsupportedChild =
            validAggregateObject().apply {
                remove("schemaVersion")
                getJSONObject("stream")
                    .put("schemaVersion", 2)
                    .put("futureStreamField", true)
            }
        val unsupportedBytes = unsupportedChild.toString().toByteArray()
        val unsupportedOriginal = unsupportedBytes.copyOf()
        assertThrows(UnsupportedCoreSchemaException::class.java) {
            CoreStateCodec.recoverableRecords(unsupportedBytes)
        }
        assertTrue(unsupportedBytes.contentEquals(unsupportedOriginal))

        val extendedChild =
            validAggregateObject().apply {
                put("schemaVersion", "one")
                getJSONObject("flagContext").put("futureFlagField", true)
            }
        val extendedBytes = extendedChild.toString().toByteArray()
        val extendedOriginal = extendedBytes.copyOf()
        val extension =
            assertThrows(UnsupportedCoreSchemaExtensionException::class.java) {
                CoreStateCodec.recoverableRecords(extendedBytes)
            }
        assertEquals("flagContext", extension.recordPath)
        assertTrue(extendedBytes.contentEquals(extendedOriginal))
    }

    @Test
    fun `structural scan allows the wrapper-adjusted boundary and rejects the next depth`() {
        assertThrows(UnsupportedCoreSchemaExtensionException::class.java) {
            CoreStateCodec.decode(aggregateWithUnknownValueAtDepth(68))
        }

        val error =
            assertThrows(CoreStateCorruptionException::class.java) {
                CoreStateCodec.decode(aggregateWithUnknownValueAtDepth(69))
            }
        assertTrue(error.message.orEmpty().contains("maximum JSON nesting depth"))
    }

    @Test
    fun `deepest accepted customer JSON round trips within the wrapper allowance`() {
        var nested: Map<String, Any?> = emptyMap()
        repeat(64) { level -> nested = mapOf("level-$level" to nested) }
        val state = CoreStateCodec.decode(validAggregateBytes())
        val atLimit =
            state.copy(
                flagContext =
                    state.flagContext.copy(
                        groupProperties = mapOf("organization" to nested),
                    ),
            )

        val encoded = CoreStateCodec.encode(atLimit)

        assertEquals(atLimit, CoreStateCodec.decode(encoded))
    }

    @Test
    fun `structural scan ignores delimiters inside escaped JSON strings`() {
        val delimiterLookingText =
            buildString {
                append("an escaped quote: \" and escaped slash: \\\\ ")
                repeat(256) { append("{[") }
            }
        val bytes =
            validAggregateObject()
                .put("futureField", delimiterLookingText)
                .toString()
                .toByteArray()

        assertThrows(UnsupportedCoreSchemaExtensionException::class.java) {
            CoreStateCodec.decode(bytes)
        }
    }

    @Test
    fun `bounded deeply nested input is rejected without reaching the recursive parser`() {
        val bytes = aggregateWithUnknownValueAtDepth(10_000)
        assertTrue(bytes.size < MAX_PERSISTED_CORE_STATE_BYTES)

        val error =
            assertThrows(CoreStateCorruptionException::class.java) {
                CoreStateCodec.decode(bytes)
            }
        assertTrue(error.message.orEmpty().contains("maximum JSON nesting depth"))
    }

    @Test
    fun `codec enforces the persisted byte cap for decoding and encoding`() {
        val oversizedBytes = ByteArray(MAX_PERSISTED_CORE_STATE_BYTES + 1) { 0x7b.toByte() }
        val decodeError =
            assertThrows(CoreStateCorruptionException::class.java) {
                CoreStateCodec.decode(oversizedBytes)
            }
        assertTrue(decodeError.message.orEmpty().contains("maximum persisted size"))

        val state = CoreStateCodec.decode(validAggregateBytes())
        val oversizedState =
            state.copy(
                identity =
                    state.identity.copy(
                        superProperties = mapOf("oversized" to "x".repeat(MAX_PERSISTED_CORE_STATE_BYTES)),
                    ),
            )
        val encodeError =
            assertThrows(CoreStateCorruptionException::class.java) {
                CoreStateCodec.encode(oversizedState)
            }
        assertTrue(encodeError.message.orEmpty().contains("maximum persisted size"))
    }

    @Test
    fun `pre-encode node budget rejects many individually valid tiny values`() {
        val state = CoreStateCodec.decode(validAggregateBytes())
        val manyTinyValues = List(70_000) { null }
        val overBudget =
            state.copy(
                identity =
                    state.identity.copy(
                        superProperties = mapOf("many" to manyTinyValues),
                    ),
            )

        val error =
            assertThrows(CoreStateCorruptionException::class.java) {
                CoreStateCodec.encode(overBudget)
            }

        assertTrue(error.message.orEmpty().contains("node budget before materialization"))
    }

    @Test
    fun `pre-encode byte budget aggregates escaped UTF-8 across strings`() {
        val state = CoreStateCodec.decode(validAggregateBytes())
        val individuallyBounded = "\ud83d\udca1".repeat(90_000)
        assertTrue(individuallyBounded.toByteArray().size < MAX_PERSISTED_CORE_STATE_BYTES)
        val overBudget =
            state.copy(
                identity =
                    state.identity.copy(
                        superProperties =
                            mapOf(
                                "first" to individuallyBounded,
                                "second" to individuallyBounded,
                                "third" to individuallyBounded,
                            ),
                    ),
            )

        val error =
            assertThrows(CoreStateCorruptionException::class.java) {
                CoreStateCodec.encode(overBudget)
            }

        assertTrue(error.message.orEmpty().contains("size estimate before JSON materialization"))
    }

    @Test
    fun `supported numbers are canonical before use and stable across Android persistence`() {
        val state = CoreStateCodec.decode(validAggregateBytes())
        val twoTo53 = Math.scalb(1.0, 53)
        val positiveLongLimit = Long.MAX_VALUE.toDouble()
        val inputNumbers: Map<String, Any?> =
            mapOf(
                "byte" to 7.toByte(),
                "short" to 300.toShort(),
                "int" to Int.MIN_VALUE,
                "smallLong" to 7L,
                "long" to Long.MAX_VALUE,
                "float" to 1.25f,
                "negativeZero" to -0.0,
                "zero" to 0.0,
                "one" to 1.0,
                "belowTwoTo53" to Math.nextDown(twoTo53),
                "twoTo53" to twoTo53,
                "aboveTwoTo53" to Math.nextUp(twoTo53),
                "belowPositiveLongLimit" to Math.nextDown(positiveLongLimit),
                "abovePositiveLongLimit" to Math.nextUp(positiveLongLimit),
                "negativeLongLimit" to Long.MIN_VALUE.toDouble(),
                "doubleMin" to Double.MIN_VALUE,
                "doubleMax" to Double.MAX_VALUE,
            )
        val expected: Map<String, Any?> =
            mapOf(
                "byte" to 7,
                "short" to 300,
                "int" to Int.MIN_VALUE,
                "smallLong" to 7,
                "long" to Long.MAX_VALUE,
                "float" to 1.25,
                "negativeZero" to 0,
                "zero" to 0,
                "one" to 1,
                "belowTwoTo53" to Math.nextDown(twoTo53).toLong(),
                "twoTo53" to twoTo53.toLong(),
                "aboveTwoTo53" to Math.nextUp(twoTo53).toLong(),
                "belowPositiveLongLimit" to Math.nextDown(positiveLongLimit).toLong(),
                "abovePositiveLongLimit" to Math.nextUp(positiveLongLimit),
                "negativeLongLimit" to Long.MIN_VALUE,
                "doubleMin" to Double.MIN_VALUE,
                "doubleMax" to Double.MAX_VALUE,
            )
        val normalized = JsonValues.objectValue(inputNumbers, "properties")
        val withNumbers =
            state.copy(
                identity =
                    state.identity.copy(
                        superProperties = normalized,
                    ),
            )

        val decoded =
            CoreStateCodec
                .decode(CoreStateCodec.encode(withNumbers))
                .identity
                .superProperties

        assertEquals(expected, normalized)
        assertEquals(expected, decoded)
    }

    @Test
    fun `host decimal decoding applies Android canonicalization and boundary rejection`() {
        val canonicalIdentity =
            JSONObject(IDENTITY_FIXTURE).apply {
                getJSONObject("superProperties").put("one", BigDecimal("1.0"))
            }

        val one = CoreStateCodec.decodeIdentity(canonicalIdentity).superProperties["one"]

        assertEquals(1, one)
        assertTrue(one is Int)

        val lossyIdentity =
            JSONObject(IDENTITY_FIXTURE).apply {
                getJSONObject("superProperties")
                    .put("lossy", BigDecimal.valueOf(Long.MAX_VALUE.toDouble()))
            }

        val error =
            assertThrows(CoreStateCorruptionException::class.java) {
                CoreStateCodec.decodeIdentity(lossyIdentity)
            }
        assertTrue(error.message.orEmpty().contains("without changing its value"))
    }

    @Test
    fun `arbitrary precision numbers are rejected before Android JSON coercion`() {
        val state = CoreStateCodec.decode(validAggregateBytes())
        val unsupportedNumbers =
            listOf(
                BigDecimal(BigInteger.ONE, Int.MAX_VALUE),
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
            )

        unsupportedNumbers.forEach { number ->
            val validationError =
                assertThrows(IllegalArgumentException::class.java) {
                    JsonValues.objectValue(mapOf("bad" to number), "properties")
                }
            assertTrue(validationError.message.orEmpty().contains("unsupported JSON number type"))

            val codecError =
                assertThrows(CoreStateCorruptionException::class.java) {
                    CoreStateCodec.encode(
                        state.copy(
                            identity = state.identity.copy(superProperties = mapOf("bad" to number)),
                        ),
                    )
                }
            assertTrue(codecError.message.orEmpty().contains("unsupported JSON number type"))
        }
    }

    @Test
    fun `finite Double at the lossy Android Long boundary is rejected before persistence`() {
        val state = CoreStateCodec.decode(validAggregateBytes())
        val lossyBoundary = Long.MAX_VALUE.toDouble()
        assertEquals(Math.scalb(1.0, 63), lossyBoundary, 0.0)

        val validationError =
            assertThrows(IllegalArgumentException::class.java) {
                JsonValues.objectValue(mapOf("bad" to lossyBoundary), "properties")
            }
        assertTrue(validationError.message.orEmpty().contains("without changing its value"))

        val codecError =
            assertThrows(CoreStateCorruptionException::class.java) {
                CoreStateCodec.encode(
                    state.copy(
                        identity = state.identity.copy(superProperties = mapOf("bad" to lossyBoundary)),
                    ),
                )
            }
        assertTrue(codecError.message.orEmpty().contains("without changing its value"))
    }

    @Test
    fun `customer JSON and codec reject every non-finite number`() {
        val state = CoreStateCodec.decode(validAggregateBytes())
        val nonFiniteNumbers =
            listOf<Number>(
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Float.NaN,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
            )

        nonFiniteNumbers.forEach { number ->
            assertThrows(IllegalArgumentException::class.java) {
                JsonValues.objectValue(mapOf("nested" to listOf(number)), "properties")
            }
            assertThrows(CoreStateCorruptionException::class.java) {
                CoreStateCodec.encode(
                    state.copy(
                        flagContext = state.flagContext.copy(personProperties = mapOf("bad" to number)),
                    ),
                )
            }
        }
    }

    @Test
    fun `customer JSON rejects non-native values`() {
        assertThrows(IllegalArgumentException::class.java) {
            JsonValues.objectValue(mapOf("bad" to Any()), "properties")
        }
    }

    private companion object {
        fun validAggregateObject(): JSONObject =
            JSONObject()
                .put("schemaVersion", 1)
                .put("identity", JSONObject(IDENTITY_FIXTURE))
                .put(
                    "stream",
                    JSONObject()
                        .put("schemaVersion", 1)
                        .put("streamId", "stream_018fcb7cff80721387a19b859e7c53a6")
                        .put("nextSequence", 42),
                ).put(
                    "flagContext",
                    JSONObject()
                        .put("schemaVersion", 1)
                        .put("personProperties", JSONObject().put("plan", "growth"))
                        .put(
                            "groupProperties",
                            JSONObject().put(
                                "organization",
                                JSONObject().put("tier", "design-partner"),
                            ),
                        ),
                )

        fun validAggregateBytes(): ByteArray = validAggregateObject().toString().toByteArray()

        fun aggregateWithUnknownValueAtDepth(maximumDepth: Int): ByteArray {
            require(maximumDepth >= 2)
            val arrayCount = maximumDepth - 1 // The aggregate root is structural depth one.
            val aggregate = validAggregateObject().toString()
            return buildString(aggregate.length + (arrayCount * 2) + 24) {
                append(aggregate, 0, aggregate.length - 1)
                append(",\"futureField\":")
                repeat(arrayCount) { append('[') }
                append("null")
                repeat(arrayCount) { append(']') }
                append('}')
            }.toByteArray()
        }

        val IDENTITY_FIXTURE =
            """
            {
              "schemaVersion": 1,
              "revision": 1,
              "contextRevision": 7,
              "anonymousId": "anon_018fcb7cff80721387a19b859e7c53a6",
              "userId": "user_123",
              "groups": { "organization": "org_456" },
              "superProperties": { "plan": "growth" },
              "session": {
                "id": "session_018fcb7cff80721387a19b859e7c53a6",
                "startedAt": "2026-08-04T00:00:00.000Z",
                "lastActivityAt": "2026-08-04T00:01:00.000Z",
                "timeoutSeconds": 1800,
                "maximumDurationSeconds": 86400,
                "lifecycle": "active",
                "backgroundedAt": null
              },
              "optedOut": false,
              "updatedAt": "2026-08-04T00:01:00.000Z"
            }
            """.trimIndent()
    }
}
