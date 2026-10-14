package dev.elu.analytics.internal.replay

import dev.elu.analytics.internal.core.CoreStateCodec
import dev.elu.analytics.internal.config.V1ConfigJson
import dev.elu.analytics.internal.config.V1ParsedConfig
import dev.elu.analytics.internal.config.V1ConfigStatus
import dev.elu.analytics.internal.config.V1ReplayTransport
import dev.elu.analytics.internal.runtime.MAX_RUNTIME_QUEUE_BYTES
import dev.elu.analytics.internal.runtime.MAX_RUNTIME_QUEUE_RECORDS
import dev.elu.analytics.internal.runtime.RuntimeQueueCorruptionException
import dev.elu.analytics.internal.runtime.RuntimeQueueTransaction
import dev.elu.analytics.internal.runtime.RuntimeReplayStoredRow
import java.security.MessageDigest

internal enum class ReplayAppendRejection { AUTHORITY, COUNT_LIMIT, BYTE_LIMIT, CONFLICT, EXPIRED, CLOCK }
internal sealed interface ReplayAppendResult {
    data class Stored(val ordinal: Long, val duplicate: Boolean) : ReplayAppendResult
    data class Rejected(val reason: ReplayAppendRejection) : ReplayAppendResult
}

/** Only the reviewed platform policy implementation may supply this relation. Default denies. */
internal fun interface ReplayMaskingRetention {
    fun mayRetain(stored: ReplayMaskingProfile, required: V1ParsedConfig): Boolean
}

/** Single owner-lane transaction primitives. No method grants network or recording authority. */
internal object ReplayQueueStore {
    fun state(tx: RuntimeQueueTransaction): ReplayStoredState? {
        if (!tx.replaySchemaPresent()) return null
        return checked { ReplayStoredState.decode(tx.readReplayRow("state") ?: corrupt("Missing replay state")) }
    }

    fun headers(tx: RuntimeQueueTransaction): List<ReplayStoredHeader> {
        val result = mutableListOf<ReplayStoredHeader>()
        tx.scanReplayRows("chunk/") { row ->
            if (result.size == MAX_RUNTIME_QUEUE_RECORDS) corrupt("Replay count exceeds its bound")
            result += checked { ReplayStoredHeader.decode(row) }
        }
        return result
    }

    private fun body(tx: RuntimeQueueTransaction, header: ReplayStoredHeader): ByteArray {
        val bytes = ByteArray(header.length)
        var offset = 0
        for (index in 0 until header.segmentCount) {
            val segment = tx.readReplayRow(header.segmentKey(index)) ?: corrupt("Missing replay body segment")
            require(segment.storageSchemaVersion == 1L && segment.payload.size == minOf(REPLAY_SEGMENT_BYTES, bytes.size - offset))
            segment.payload.copyInto(bytes, offset)
            offset += segment.payload.size
        }
        require(ReplayJson.digest(bytes) == header.digest)
        return bytes
    }

    fun read(tx: RuntimeQueueTransaction, header: ReplayStoredHeader): ReplayStoredChunk = checked {
        require(!header.raster)
        val bytes = body(tx, header)
        val prepared = try { PreparedReplayRequest.parse(bytes, header.generation) } finally { bytes.fill(0) }
        require(prepared.requestId == header.requestId && prepared.replayId == header.replayId &&
            prepared.chunkId == header.chunkId && prepared.sequence == header.sequence && prepared.maskingProfileHash == checkNotNull(header.profile).hash)
        ReplayStoredChunk(header.ordinal, header.siteId, header.generation, prepared, checkNotNull(header.profile))
    }

    fun readRaster(tx: RuntimeQueueTransaction, header: ReplayStoredHeader): NativeRasterStoredChunk = checked {
        require(header.raster && tx.nativeRasterReplaySchemaPresent())
        val bytes = body(tx, header)
        val request = try { NativeRasterStoredRequest.parse(bytes) } finally { bytes.fill(0) }
        require(request.requestId == header.requestId && request.replayId == header.replayId &&
            request.chunkId == header.chunkId && request.sequence == header.sequence)
        NativeRasterStoredChunk(header.ordinal, header.siteId, request)
    }

    fun validate(tx: RuntimeQueueTransaction, namespace: String?): ReplayStoredState? = checked {
        val state = state(tx) ?: return@checked null
        require(namespace != null && state.namespaceHash == namespace)
        require(state.rasterStorage == tx.nativeRasterReplaySchemaPresent())
        require(state.count in 0..MAX_RUNTIME_QUEUE_RECORDS.toLong() && state.bytes in 0..MAX_RUNTIME_QUEUE_BYTES)
        if (state.issuedAt.isEmpty()) require(state.semanticHash.isEmpty() && state.siteId.isEmpty() && state.protocol.isEmpty() && state.count == 0L)
        else {
            V1ConfigJson.parseExactTimestamp(state.issuedAt)
            require(state.semanticHash.matches(Regex("sha256:[a-f0-9]{64}")))
        }
        val rows = headers(tx)
        var total = 0L
        var deliveryCount = 0L
        val expectedKeys = hashSetOf("state")
        if (validateNative(tx, namespace, CoreStateCodec.decode(checkNotNull(tx.readCore()).stateJson).stream.streamId) != null) {
            expectedKeys += NativeReplayAccounting.KEY
        }
        val requests = hashSetOf<String>()
        val chunks = hashSetOf<Pair<String, String>>()
        val sequences = hashSetOf<Pair<String, Long>>()
        rows.forEach { header ->
            require(header.ordinal < state.nextOrdinal && header.siteId == state.siteId &&
                (if (header.raster) state.rasterStorage && header.generation == NativeRasterSealer.GENERATION else header.generation == state.protocol))
            require(requests.add(header.requestId) && chunks.add(header.replayId to header.chunkId) && sequences.add(header.replayId to header.sequence))
            if (header.raster) readRaster(tx, header) else read(tx, header)
            total = Math.addExact(total, header.length.toLong())
            require(total <= MAX_RUNTIME_QUEUE_BYTES)
            tx.readReplayRow(ReplayDeliveryMetadata.key(header.ordinal))?.let { metadata ->
                val delivery = ReplayDeliveryMetadata.decode(metadata)
                require(delivery.ordinal == header.ordinal && delivery.digest == header.digest && delivery.protocolGeneration == header.generation &&
                    delivery.raster == header.raster && (!header.raster || delivery.replayId == header.replayId))
                expectedKeys += metadata.key
                deliveryCount++
            }
            expectedKeys += header.key
            for (i in 0 until header.segmentCount) expectedKeys += header.segmentKey(i)
        }
        tx.scanReplayRows("") { require(expectedKeys.remove(it.key)) { "Unexpected replay row" } }
        require(expectedKeys.isEmpty() && rows.size.toLong() == state.count && total == state.bytes)
        require((state.deliveryMetadataCount ?: 0L) in 0..state.count &&
            deliveryCount == (state.deliveryMetadataCount ?: 0L)) { "Missing replay delivery metadata" }
        val core = tx.readCore() ?: corrupt("Replay rows without core")
        require(core.queueCount + state.count <= MAX_RUNTIME_QUEUE_RECORDS && core.queueBytes + state.bytes <= MAX_RUNTIME_QUEUE_BYTES)
        state
    }

    /** The schema marker and required native row are checked even on ordinary core transactions. */
    fun validateNative(tx: RuntimeQueueTransaction, namespace: String?, streamId: String): NativeReplaySessionState? = checked {
        if (!tx.replaySchemaPresent()) {
            require(!tx.nativeReplaySchemaPresent())
            return@checked null
        }
        val row = tx.readReplayRow(NativeReplayAccounting.KEY)
        if (!tx.nativeReplaySchemaPresent()) {
            require(row == null) { "Native metadata precedes its schema" }
            return@checked null
        }
        val native = NativeReplayAccounting.read(row ?: corrupt("Missing native replay accounting"))
        require(namespace != null && native.namespaceHash == namespace && native.streamId == streamId)
        native
    }

    /** Exact all-domain fingerprint for ambiguous commits, including core, rows and segments. */
    fun fingerprint(tx: RuntimeQueueTransaction): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(bytes: ByteArray) {
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        val core = tx.readCore() ?: corrupt("Missing runtime core")
        add(core.stateJson); add(core.queueCount.toString().toByteArray()); add(core.queueBytes.toString().toByteArray())
        tx.scanRecords { add(it.sequence.toString().toByteArray()); add(it.internalPayload); add(it.accountedBytes.toString().toByteArray()) }
        tx.scanReplayRows("") { add(it.key.toByteArray()); add(it.storageSchemaVersion.toString().toByteArray()); add(it.payload) }
        return "sha256:" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Explicit restrictions purge; expired/unavailable configuration is handled by the owner. */
    fun reconcile(tx: RuntimeQueueTransaction, config: V1ParsedConfig, namespace: String, now: Long,
        optedOut: Boolean, proven: Set<V1ReplayTransport>, supportedGenerations: Set<String>, retention: ReplayMaskingRetention,
        mayRemoveRaster: (String) -> Boolean = { true }): Boolean {
        var state = state(tx) ?: corrupt("Replay schema not initialized")
        require(state.namespaceHash == namespace)
        if (state.clockDenied || now < state.wallFloor) return false
        if (state.issuedAt.isNotEmpty()) {
            val order = config.issuedAtInstant.compareTo(V1ConfigJson.parseExactTimestamp(state.issuedAt))
            if (order < 0 || (order == 0 && state.poisoned)) return false
            if (order == 0 && state.semanticHash != config.configSemanticHash) {
                tx.putReplayRow(state.copy(poisoned = true, wallFloor = now).row())
                return false
            }
        }
        if (config.siteId != null && state.siteId.isNotEmpty() && state.siteId != config.siteId) return false
        val protocol = config.replayCapabilities?.replayProtocolGeneration.orEmpty()
        val advertised = config.replayCapabilities?.advertisedTransports.orEmpty()
        val disabled = config.status != V1ConfigStatus.ENABLED || config.features?.replay != true ||
            config.privacy?.replay?.enabled != true || config.features?.capture != true ||
            config.privacy?.capture?.enabled != true || optedOut || protocol !in supportedGenerations
        val generationChanged = state.protocol.isNotEmpty() && state.protocol != protocol
        val all = headers(tx)
        val expiredRaster = all.filter { it.raster && readRaster(tx, it).request.expiredAt(now) }.map { it.replayId }.toSet()
        val retiredRaster = rasterRetirementEpochs(tx, all)
        val retiring = hashSetOf<String>()
        val remove = all.filter { header ->
            if (header.raster) {
                val restricted = optedOut || !nativeRasterBaseMayRetain(config) ||
                    (config.siteId != null && config.siteId != header.siteId) || header.replayId in expiredRaster || header.replayId in retiredRaster
                if (!restricted) return@filter false
                if (mayRemoveRaster(header.replayId)) return@filter true
                if (retiring.add(header.replayId)) markRasterRetiring(tx, state, all, header.replayId)
                return@filter false
            }
            val row = read(tx, header)
            disabled || generationChanged || row.prepared.transport !in advertised || row.prepared.transport !in proven ||
                row.prepared.expiredAt(now) || !retention.mayRetain(row.maskingProfile, config)
        }
        state = delete(tx, checkNotNull(state(tx)), remove)
        tx.putReplayRow(state.copy(siteId = config.siteId ?: state.siteId, issuedAt = config.issuedAt,
            semanticHash = config.configSemanticHash, protocol = protocol, wallFloor = now, poisoned = false).row())
        return !disabled
    }

    fun expire(tx: RuntimeQueueTransaction, now: Long, mayRemoveRaster: (String) -> Boolean = { true }): Int {
        val current = state(tx) ?: return 0
        if (current.clockDenied || now < current.wallFloor) return 0
        val all = headers(tx)
        val expiredEpochs = all.filter { it.raster && readRaster(tx, it).request.expiredAt(now) }.map { it.replayId }.toSet()
        val retiredEpochs = rasterRetirementEpochs(tx, all)
        val retiring = hashSetOf<String>()
        val expired = all.filter {
            if (!it.raster) return@filter read(tx, it).prepared.expiredAt(now)
            if (it.replayId !in expiredEpochs && it.replayId !in retiredEpochs) return@filter false
            if (mayRemoveRaster(it.replayId)) return@filter true
            if (retiring.add(it.replayId)) markRasterRetiring(tx, current, all, it.replayId)
            false
        }
        tx.putReplayRow(delete(tx, checkNotNull(state(tx)), expired).copy(wallFloor = now).row())
        return expired.size
    }

    fun purge(tx: RuntimeQueueTransaction, mayRemoveRaster: (String) -> Boolean = { true }) {
        val current = state(tx) ?: return
        val all = headers(tx); val retiring = hashSetOf<String>()
        val remove = all.filter {
            if (!it.raster || mayRemoveRaster(it.replayId)) true
            else { if (retiring.add(it.replayId)) markRasterRetiring(tx, current, all, it.replayId); false }
        }
        tx.putReplayRow(delete(tx, checkNotNull(state(tx)), remove).row())
    }

    fun append(tx: RuntimeQueueTransaction, request: PreparedReplayRequest, profile: ReplayMaskingProfile,
        namespace: String, siteId: String, protocol: String, now: Long, maximumCount: Int,
        maximumBytes: Long, requestMaximum: Int, admissionNow: () -> Long?): ReplayAppendResult {
        val state = state(tx) ?: corrupt("Replay schema not initialized")
        require(state.namespaceHash == namespace)
        if (state.poisoned || state.siteId != siteId || state.protocol != protocol || request.captureProtocolGeneration != protocol) return ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY)
        if (state.clockDenied || now < state.wallFloor) return ReplayAppendResult.Rejected(ReplayAppendRejection.CLOCK)
        if (request.expiredAt(now)) return ReplayAppendResult.Rejected(ReplayAppendRejection.EXPIRED)
        require(request.maskingProfileHash == profile.hash)
        val rows = headers(tx)
        val collisions = rows.filter { it.requestId == request.requestId ||
            (it.replayId == request.replayId && (it.chunkId == request.chunkId || it.sequence == request.sequence)) }
        if (collisions.isNotEmpty()) {
            val existing = collisions.singleOrNull()
            if (existing == null || existing.raster || existing.digest != request.digest || checkNotNull(existing.profile).hash != profile.hash ||
                !read(tx, existing).prepared.copyBytes().contentEquals(request.copyBytes()))
                return ReplayAppendResult.Rejected(ReplayAppendRejection.CONFLICT)
            val finalNow = admissionNow() ?: return ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY)
            if (request.expiredAt(finalNow)) return ReplayAppendResult.Rejected(ReplayAppendRejection.EXPIRED)
            return ReplayAppendResult.Stored(existing.ordinal, true)
        }
        val core = tx.readCore() ?: corrupt("Replay append without core")
        if (core.queueCount + state.count + 1 > maximumCount) return ReplayAppendResult.Rejected(ReplayAppendRejection.COUNT_LIMIT)
        if (request.byteCount > requestMaximum || core.queueBytes + state.bytes + request.byteCount > maximumBytes)
            return ReplayAppendResult.Rejected(ReplayAppendRejection.BYTE_LIMIT)
        require(state.nextOrdinal < MAX_REPLAY_SAFE_INTEGER)
        val header = ReplayStoredHeader(state.nextOrdinal, siteId, protocol, request.requestId, request.replayId,
            request.chunkId, request.sequence, request.byteCount, request.digest, profile)
        val bytes = request.copyBytes()
        val headerRow = header.row()
        // All full-request validation/copying has finished. Consume current admission immediately
        // before the first SQLite row write, not before those potentially expensive operations.
        val finalNow = admissionNow() ?: return ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY)
        if (request.expiredAt(finalNow)) return ReplayAppendResult.Rejected(ReplayAppendRejection.EXPIRED)
        tx.putReplayRow(headerRow)
        for (i in 0 until header.segmentCount) tx.putReplayRow(RuntimeReplayStoredRow(header.segmentKey(i), 1,
            bytes.copyOfRange(i * REPLAY_SEGMENT_BYTES, minOf(bytes.size, (i + 1) * REPLAY_SEGMENT_BYTES))))
        tx.putReplayRow(state.copy(nextOrdinal = state.nextOrdinal + 1, count = state.count + 1,
            bytes = state.bytes + request.byteCount, wallFloor = finalNow).row())
        return ReplayAppendResult.Stored(header.ordinal, false)
    }

    /** Metadata-only restriction: commit before returning denial, independent of replay clock health. */
    fun rasterOrdering(tx: RuntimeQueueTransaction, source: dev.elu.analytics.internal.config.V2ConfigAuthorityWitness,
        config: V1ParsedConfig): Boolean {
        val state = checkNotNull(state(tx)); check(state.rasterStorage)
        if (state.issuedAt.isNotEmpty()) {
            val baseOrder = config.issuedAtInstant.compareTo(V1ConfigJson.parseExactTimestamp(state.issuedAt))
            if (baseOrder < 0 || (baseOrder == 0 && state.poisoned)) return false
            if (baseOrder == 0 && state.semanticHash != config.configSemanticHash) {
                tx.putReplayRow(state.copy(poisoned = true).row()); return false
            }
        }
        val old = state.rasterSource
        val wrapper = source.nativeV3
        if (wrapper == null) return old == null || !old.conflicted ||
            config.issuedAtInstant > V1ConfigJson.parseExactTimestamp(old.issuedAt)
        val (next, allowed) = old?.observe(wrapper.base.issuedAt, wrapper.semanticHash)
            ?: (NativeRasterSourceLedger(wrapper.base.issuedAt, wrapper.semanticHash) to true)
        if (next != old) tx.putReplayRow(state.copy(rasterSource = next).row())
        return allowed
    }

    fun recordRasterDenial(tx: RuntimeQueueTransaction, receipt: dev.elu.analytics.internal.config.V2RasterConflictReceipt) {
        val state = checkNotNull(state(tx)); check(state.rasterStorage)
        if (state.issuedAt.isNotEmpty() && V1ConfigJson.parseExactTimestamp(state.issuedAt) >
            V1ConfigJson.parseExactTimestamp(receipt.issuedAt)) return
        val old = state.rasterSource
        val order = old?.let { V1ConfigJson.parseExactTimestamp(receipt.issuedAt).compareTo(V1ConfigJson.parseExactTimestamp(it.issuedAt)) }
        val next = when {
            order == null || order > 0 -> NativeRasterSourceLedger(receipt.issuedAt, receipt.previousSemanticHash, true)
            order == 0 -> checkNotNull(old).copy(conflicted = true)
            else -> old
        }
        if (next != old) tx.putReplayRow(state.copy(rasterSource = next).row())
    }

    fun appendRaster(tx: RuntimeQueueTransaction, request: NativeRasterStoredRequest, namespace: String,
        siteId: String, wrapper: dev.elu.analytics.internal.config.NativeV3ConfigParser.Parsed,
        now: Long, maximumCount: Int, maximumBytes: Long, requestMaximum: Int,
        admissionNow: () -> Long?): ReplayAppendResult {
        val state = checkNotNull(state(tx)); require(state.namespaceHash == namespace && state.rasterStorage)
        val ledger = state.rasterSource
        if (state.poisoned || state.siteId != siteId || ledger == null || ledger.conflicted ||
            ledger.semanticHash != wrapper.semanticHash || V1ConfigJson.parseExactTimestamp(ledger.issuedAt).compareTo(wrapper.base.issuedAtInstant) != 0)
            return ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY)
        if (state.clockDenied || now < state.wallFloor) return ReplayAppendResult.Rejected(ReplayAppendRejection.CLOCK)
        if (request.expiredAt(now)) return ReplayAppendResult.Rejected(ReplayAppendRejection.EXPIRED)
        val rows = headers(tx)
        if (rasterEpochBlocked(tx, request.replayId)) return ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY)
        val collisions = rows.filter { it.requestId == request.requestId ||
            (it.replayId == request.replayId && (it.chunkId == request.chunkId || it.sequence == request.sequence)) }
        if (collisions.isNotEmpty()) {
            val existing = collisions.singleOrNull()
            if (existing == null || !existing.raster || existing.digest != request.digest ||
                !readRaster(tx, existing).request.copyBytes().contentEquals(request.copyBytes()))
                return ReplayAppendResult.Rejected(ReplayAppendRejection.CONFLICT)
            val finalNow = admissionNow() ?: return ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY)
            return if (request.expiredAt(finalNow)) ReplayAppendResult.Rejected(ReplayAppendRejection.EXPIRED)
                else ReplayAppendResult.Stored(existing.ordinal, true)
        }
        val core = checkNotNull(tx.readCore())
        if (core.queueCount + state.count + 1 > maximumCount) return ReplayAppendResult.Rejected(ReplayAppendRejection.COUNT_LIMIT)
        if (request.byteCount > requestMaximum || core.queueBytes + state.bytes + request.byteCount > maximumBytes)
            return ReplayAppendResult.Rejected(ReplayAppendRejection.BYTE_LIMIT)
        require(state.nextOrdinal < MAX_REPLAY_SAFE_INTEGER)
        val header = ReplayStoredHeader(state.nextOrdinal, siteId, NativeRasterSealer.GENERATION, request.requestId,
            request.replayId, request.chunkId, request.sequence, request.byteCount, request.digest, null, true)
        val bytes = request.copyBytes()
        try {
            val row = header.row()
            val finalNow = admissionNow() ?: return ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY)
            if (request.expiredAt(finalNow)) return ReplayAppendResult.Rejected(ReplayAppendRejection.EXPIRED)
            tx.putReplayRow(row)
            for (i in 0 until header.segmentCount) tx.putReplayRow(RuntimeReplayStoredRow(header.segmentKey(i), 1,
                bytes.copyOfRange(i * REPLAY_SEGMENT_BYTES, minOf(bytes.size, (i + 1) * REPLAY_SEGMENT_BYTES))))
            tx.putReplayRow(state.copy(nextOrdinal = state.nextOrdinal + 1, count = state.count + 1,
                bytes = state.bytes + request.byteCount, wallFloor = finalNow).row())
            return ReplayAppendResult.Stored(header.ordinal, false)
        } finally { bytes.fill(0) }
    }

    fun delivery(tx: RuntimeQueueTransaction, ordinal: Long): ReplayDeliveryMetadata? =
        tx.readReplayRow(ReplayDeliveryMetadata.key(ordinal))?.let { checked { ReplayDeliveryMetadata.decode(it) } }

    fun rasterEpochBlocked(tx: RuntimeQueueTransaction, replayId: String): Boolean =
        headers(tx).filter { it.raster && it.replayId == replayId }.any {
            delivery(tx, it.ordinal)?.state == ReplayDeliveryState.BLOCKED
        }

    private fun rasterRetirementEpochs(tx: RuntimeQueueTransaction, rows: List<ReplayStoredHeader>): Set<String> =
        rows.filter { it.raster && delivery(tx, it.ordinal)?.let { d ->
            d.state == ReplayDeliveryState.BLOCKED && d.blockKind == ReplayBlockKind.RASTER_RETIRE.name
        } == true }.map { it.replayId }.toSet()

    /** Restriction provenance only: no attempt, owner/nonce, credential or allow authority is minted. */
    private fun markRasterRetiring(tx: RuntimeQueueTransaction, original: ReplayStoredState,
        rows: List<ReplayStoredHeader>, replayId: String) {
        require(original.rasterStorage && original.namespaceHash.isNotEmpty())
        val anchor = checkNotNull(original.rasterSource).issuedAt
        var added = 0L
        rows.filter { it.raster && it.replayId == replayId }.forEach { header ->
            require(header.siteId == original.siteId && header.generation == NativeRasterSealer.GENERATION)
            val prior = delivery(tx, header.ordinal)
            if (prior?.blockKind == ReplayBlockKind.RASTER_RETIRE.name) return@forEach
            val restriction = ReplayDeliveryMetadata(header.ordinal, header.digest, ReplayDeliveryState.BLOCKED, "", "", 0,
                blockKind = ReplayBlockKind.RASTER_RETIRE.name, protocolGeneration = header.generation,
                raster = true, replayId = header.replayId, sourceIssuedAt = prior?.sourceIssuedAt ?: anchor)
            tx.putReplayRow(restriction.row())
            if (prior == null) added++
        }
        if (added > 0) {
            val state = checkNotNull(state(tx))
            tx.putReplayRow(state.copy(deliveryMetadataCount = (state.deliveryMetadataCount ?: 0L) + added).row())
        }
    }

    fun claim(tx: RuntimeQueueTransaction, owner: String, authorization: ReplayDeliveryAuthorization,
        now: Long, elapsedMillis: Long,
        originalSource: dev.elu.analytics.internal.config.V2ConfigAuthorityWitness? = null,
        onlyOrdinal: Long? = null, finalAdmission: () -> Boolean): ReplayDeliveryClaim? {
        require(elapsedMillis in 0..MAX_REPLAY_SAFE_INTEGER - REPLAY_MAX_RETRY_MILLIS)
        val state = state(tx) ?: return null
        if (state.poisoned || state.clockDenied || now < state.wallFloor) return null
        val raster = authorization.format == ReplayDeliveryFormat.RASTER
        val all = headers(tx)
        if (all.any { delivery(tx, it.ordinal)?.let { d -> d.owner == owner &&
            d.state in setOf(ReplayDeliveryState.CLAIMED, ReplayDeliveryState.ENROLLED) } == true }) return null
        val rows = all.filter { it.raster == raster }
        var occupied = false
        var cooldown = false
        rows.forEach { header ->
            var d = delivery(tx, header.ordinal) ?: return@forEach
            if (d.state == ReplayDeliveryState.BLOCKED && (d.blockKind == ReplayBlockKind.UNAUTHORIZED.name ||
                raster && d.blockKind in setOf(ReplayBlockKind.FORBIDDEN.name, ReplayBlockKind.PROTOCOL.name)) &&
                d.credentialWitness == authorization.credentialWitness && d.scopeWitness == authorization.scopeWitness &&
                (!raster || dev.elu.analytics.internal.config.V1ConfigJson.parseExactTimestamp(authorization.sourceIssuedAt) <=
                    dev.elu.analytics.internal.config.V1ConfigJson.parseExactTimestamp(d.sourceIssuedAt))) cooldown = true
            if (d.state in setOf(ReplayDeliveryState.CLAIMED, ReplayDeliveryState.ENROLLED) && d.owner == owner) occupied = true
            if (d.state != ReplayDeliveryState.BLOCKED && d.owner != owner) {
                val delay = if (d.state in setOf(ReplayDeliveryState.CLAIMED, ReplayDeliveryState.ENROLLED)) REPLAY_UNKNOWN_ATTEMPT_DELAY_MILLIS else d.delayMillis
                d = d.copy(state = ReplayDeliveryState.RETRY, owner = owner, delayMillis = delay, notBeforeMillis = elapsedMillis + delay)
                tx.putReplayRow(d.row())
            }
            if (d.state == ReplayDeliveryState.RETRY && d.endpointCooldown && elapsedMillis < d.notBeforeMillis &&
                d.scopeWitness == authorization.scopeWitness) cooldown = true
        }
        if (occupied || cooldown) return null
        val heads = rows.groupBy { it.replayId }.values.map { group -> group.minBy { it.sequence } }.sortedBy { it.ordinal }
        for (header in heads) {
            if (onlyOrdinal != null && header.ordinal != onlyOrdinal) continue
            if (raster && rasterEpochBlocked(tx, header.replayId)) continue
            val d = delivery(tx, header.ordinal)
            if (d?.state == ReplayDeliveryState.BLOCKED || (d?.state == ReplayDeliveryState.RETRY && elapsedMillis < d.notBeforeMillis)) continue
            val row = if (!raster) read(tx, header) else null
            val rasterRow = if (raster) readRaster(tx, header) else null
            if (rasterRow != null) {
                val request = rasterRow.request
                if (originalSource == null || rasterRow.siteId != authorization.siteId || request.expiredAt(now) ||
                    request.policyRevision != authorization.policyRevision || request.effectivePolicyHash != authorization.effectivePolicyHash ||
                    request.byteCount > authorization.maximumRequestBytes) continue
            } else if (row == null || row.siteId != authorization.siteId || row.captureProtocolGeneration != authorization.protocolGeneration ||
                row.prepared.transport != authorization.transport || row.prepared.expiredAt(now)) continue
            val next = ReplayDeliveryMetadata(header.ordinal, header.digest, ReplayDeliveryState.CLAIMED, owner,
                java.util.UUID.randomUUID().toString(), minOf((d?.attempts ?: 0) + 1, 31),
                credentialWitness = authorization.credentialWitness, scopeWitness = authorization.scopeWitness,
                protocolGeneration = authorization.protocolGeneration, raster = raster,
                replayId = if (raster) header.replayId else "", sourceIssuedAt = if (raster) authorization.sourceIssuedAt else "")
            if (!finalAdmission()) return null
            tx.putReplayRow(next.row())
            val current = checkNotNull(state(tx))
            tx.putReplayRow(current.copy(wallFloor = maxOf(now, current.wallFloor),
                deliveryMetadataCount = (current.deliveryMetadataCount ?: 0L) + if (d == null) 1 else 0).row())
            return if (rasterRow != null) ReplayDeliveryClaim.raster(owner, next.nonce, rasterRow, authorization, next.attempts, checkNotNull(originalSource))
                else ReplayDeliveryClaim(owner, next.nonce, checkNotNull(row), authorization, next.attempts)
        }
        return null
    }

    fun matches(tx: RuntimeQueueTransaction, claim: ReplayDeliveryClaim): Boolean {
        val d = delivery(tx, claim.ordinal) ?: return false
        return d.raster == (claim.format == ReplayDeliveryFormat.RASTER) &&
            (!d.raster || d.replayId == claim.replayId && d.sourceIssuedAt == claim.authorization.sourceIssuedAt) &&
            d.state in setOf(ReplayDeliveryState.CLAIMED, ReplayDeliveryState.ENROLLED) && d.owner == claim.owner && d.nonce == claim.nonce &&
            d.digest == claim.digest && d.protocolGeneration == claim.authorization.protocolGeneration &&
            d.credentialWitness == claim.authorization.credentialWitness && d.scopeWitness == claim.authorization.scopeWitness
    }

    fun enroll(tx: RuntimeQueueTransaction, claim: ReplayDeliveryClaim): Boolean {
        if (!matches(tx, claim)) return false
        val d = checkNotNull(delivery(tx, claim.ordinal))
        if (d.state != ReplayDeliveryState.CLAIMED) return false
        tx.putReplayRow(d.copy(state = ReplayDeliveryState.ENROLLED).row())
        return true
    }

    fun nextWakeDelay(tx: RuntimeQueueTransaction, owner: String, authority: ReplayDeliveryAuthorization, elapsedMillis: Long,
        eligibleRaster: (NativeRasterStoredRequest) -> Boolean = { true }): Long? {
        val raster = authority.format == ReplayDeliveryFormat.RASTER
        val all = headers(tx)
        if (all.any { delivery(tx, it.ordinal)?.let { d -> d.owner == owner &&
            d.state in setOf(ReplayDeliveryState.CLAIMED, ReplayDeliveryState.ENROLLED) } == true }) return null
        val rows = all.filter { it.raster == raster }
        val metadata = rows.mapNotNull { delivery(tx, it.ordinal) }
        if (metadata.any { it.state == ReplayDeliveryState.BLOCKED && (it.blockKind == ReplayBlockKind.UNAUTHORIZED.name ||
            raster && it.blockKind in setOf(ReplayBlockKind.FORBIDDEN.name, ReplayBlockKind.PROTOCOL.name)) &&
            it.credentialWitness == authority.credentialWitness && it.scopeWitness == authority.scopeWitness &&
            (!raster || dev.elu.analytics.internal.config.V1ConfigJson.parseExactTimestamp(authority.sourceIssuedAt) <=
                dev.elu.analytics.internal.config.V1ConfigJson.parseExactTimestamp(it.sourceIssuedAt)) }) return null
        if (metadata.any { it.state in setOf(ReplayDeliveryState.CLAIMED, ReplayDeliveryState.ENROLLED) && it.owner == owner }) return null
        val heads = rows.groupBy { it.replayId }.values.map { it.minBy { row -> row.sequence } }
            .filter { !raster || !rasterEpochBlocked(tx, it.replayId) && eligibleRaster(readRaster(tx, it).request) }
        if (heads.isEmpty()) return null
        // A matching endpoint cooldown blocks every row, including already-due ordinary heads.
        // Waking for an earlier head would otherwise spin at1ms until this deadline expires.
        val endpointDeadline = metadata.filter { it.state == ReplayDeliveryState.RETRY && it.owner == owner &&
            it.endpointCooldown && it.scopeWitness == authority.scopeWitness && it.notBeforeMillis > elapsedMillis }
            .maxOfOrNull { it.notBeforeMillis }
        if (endpointDeadline != null) return endpointDeadline - elapsedMillis
        return heads.mapNotNull { delivery(tx, it.ordinal) }.filter { it.state == ReplayDeliveryState.RETRY && it.owner == owner }
            .minOfOrNull { maxOf(1L, it.notBeforeMillis - elapsedMillis) }
    }

    fun commit(tx: RuntimeQueueTransaction, claim: ReplayDeliveryClaim, outcome: ReplayDeliveryOutcome,
        elapsedMillis: Long): ReplayDeliveryCommit {
        require(elapsedMillis in 0..MAX_REPLAY_SAFE_INTEGER - REPLAY_MAX_RETRY_MILLIS)
        require(outcome != ReplayDeliveryOutcome.Blocked(ReplayBlockKind.RASTER_RETIRE))
        val retained = delivery(tx, claim.ordinal)
        // Owner settlement may have persisted this exact permanent refusal before waking the
        // coordinator or completing close. Its repeat is idempotent, never a new authority grant.
        if (outcome is ReplayDeliveryOutcome.Blocked && retained?.state == ReplayDeliveryState.BLOCKED &&
            retained.owner == claim.owner && retained.nonce == claim.nonce && retained.digest == claim.digest &&
            retained.raster == (claim.format == ReplayDeliveryFormat.RASTER) &&
            (!retained.raster || retained.replayId == claim.replayId && retained.sourceIssuedAt == claim.authorization.sourceIssuedAt) &&
            retained.blockKind == outcome.kind.name && retained.protocolGeneration == claim.authorization.protocolGeneration &&
            retained.credentialWitness == claim.authorization.credentialWitness && retained.scopeWitness == claim.authorization.scopeWitness)
            return ReplayDeliveryCommit.COMMITTED
        if (!matches(tx, claim)) return ReplayDeliveryCommit.STALE
        val d = checkNotNull(delivery(tx, claim.ordinal))
        when (outcome) {
            ReplayDeliveryOutcome.Accepted, ReplayDeliveryOutcome.RejectedTooLarge -> {
                require(claim.format != ReplayDeliveryFormat.RASTER || outcome == ReplayDeliveryOutcome.Accepted)
                val header = headers(tx).singleOrNull { it.ordinal == claim.ordinal && it.digest == claim.digest }
                    ?: return ReplayDeliveryCommit.STALE
                tx.putReplayRow(delete(tx, checkNotNull(state(tx)), listOf(header)).row())
            }
            is ReplayDeliveryOutcome.Retry -> {
                require(outcome.delayMillis in 0..REPLAY_MAX_RETRY_MILLIS)
                tx.putReplayRow(d.copy(state = ReplayDeliveryState.RETRY, delayMillis = outcome.delayMillis,
                    notBeforeMillis = elapsedMillis + outcome.delayMillis, endpointCooldown = outcome.endpointCooldown).row())
            }
            is ReplayDeliveryOutcome.Blocked -> tx.putReplayRow(d.copy(state = ReplayDeliveryState.BLOCKED,
                blockKind = outcome.kind.name, delayMillis = 0, notBeforeMillis = 0, endpointCooldown = false).row())
        }
        return ReplayDeliveryCommit.COMMITTED
    }

    /** Restriction-only removal after the original owner has settled matching physical work. */
    fun removeRasterEpochs(tx: RuntimeQueueTransaction, replayIds: Set<String>) {
        val state = state(tx) ?: return
        tx.putReplayRow(delete(tx, state, headers(tx).filter { it.raster && it.replayId in replayIds }).row())
    }

    private fun delete(tx: RuntimeQueueTransaction, state: ReplayStoredState, rows: List<ReplayStoredHeader>): ReplayStoredState {
        var bytes = state.bytes
        var deliveryCount = state.deliveryMetadataCount
        rows.forEach { header ->
            if (tx.deleteReplayRow(ReplayDeliveryMetadata.key(header.ordinal))) {
                deliveryCount = checkNotNull(deliveryCount) - 1
                check(deliveryCount >= 0)
            }
            check(tx.deleteReplayRow(header.key))
            for (i in 0 until header.segmentCount) check(tx.deleteReplayRow(header.segmentKey(i)))
            bytes -= header.length
        }
        return state.copy(count = state.count - rows.size, bytes = bytes, deliveryMetadataCount = deliveryCount)
    }
    private fun corrupt(message: String): Nothing = throw RuntimeQueueCorruptionException(message)
    private inline fun <T> checked(action: () -> T): T = try { action() }
        catch (error: RuntimeQueueCorruptionException) { throw error }
        catch (error: Exception) { throw RuntimeQueueCorruptionException("Invalid replay storage", error) }
}
