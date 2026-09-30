package dev.elu.analytics.internal.replay

import android.graphics.Bitmap
import android.graphics.Color
import java.io.OutputStream

/** Internal post-validation output. No public Bitmap getter or arbitrary image submission API. */
internal class AnnotatedRasterCandidate private constructor(
    private var bitmap: Bitmap?,
    val sourceIdentity: AnnotatedRasterSourceIdentity,
    private val current: () -> Boolean,
    private val released: () -> Unit,
) : AutoCloseable {
    val width: Int = checkNotNull(bitmap).width
    val height: Int = checkNotNull(bitmap).height

    /** One-shot worker encoding. Original request bytes, never retained pixels, serve later retries. */
    @Synchronized
    fun encodePng(): ByteArray {
        val original = checkNotNull(bitmap) { "closed-frame" }
        var output: LimitedBytes? = null
        var encoded: ByteArray? = null
        var raw: ByteArray? = null
        var failure: Throwable? = null
        try {
            val ownedOutput = LimitedBytes(DeclaredRegionPngEncoder.MAX_ENCODED_BYTES)
            output = ownedOutput
            check(current()) { "withdrawn-frame" }
            check(original.compress(Bitmap.CompressFormat.PNG, 100, ownedOutput)) { "png-encode" }
            check(current()) { "withdrawn-frame" }
            raw = ownedOutput.bytes()
            encoded = DeclaredRegionPngEncoder.normalize(checkNotNull(raw), width, height)
            check(current()) { "withdrawn-frame" }
        } catch (error: Throwable) {
            failure = error
        } finally {
            raw?.fill(0); output?.close()
            // The detached bytes remain private until original pixel cleanup succeeds.
            try { close() } catch (cleanup: Throwable) {
                val primary = failure
                if (primary == null) failure = cleanup else if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
        }
        failure?.let { encoded?.fill(0); throw it }
        return checkNotNull(encoded)
    }

    @Synchronized override fun close() {
        val original = bitmap ?: return
        bitmap = null
        try { clear(original) } finally { released() }
    }

    internal companion object {
        /** Called only after original complete collector validation. Not capture/queue authority. */
        fun validated(bitmap: Bitmap, sourceIdentity: AnnotatedRasterSourceIdentity,
            current: () -> Boolean, released: () -> Unit) =
            AnnotatedRasterCandidate(bitmap, sourceIdentity, current, released)

        fun clear(bitmap: Bitmap) {
            try { bitmap.eraseColor(Color.TRANSPARENT) } finally { bitmap.recycle() }
        }
    }
}

/** Fixed allocation avoids an unbounded Bitmap.compress ByteArrayOutputStream. */
internal class LimitedBytes(private val limit: Int) : OutputStream() {
    private val storage = ByteArray(limit)
    private var length = 0
    private var closed = false
    override fun write(value: Int) {
        check(!closed && length < limit) { "encoded-byte-limit" }; storage[length++] = value.toByte()
    }
    override fun write(bytes: ByteArray, offset: Int, count: Int) {
        require(offset >= 0 && count >= 0 && offset <= bytes.size - count)
        check(!closed && count <= limit - length) { "encoded-byte-limit" }
        bytes.copyInto(storage, length, offset, offset + count); length += count
    }
    fun bytes(): ByteArray { check(!closed); return storage.copyOf(length) }
    override fun close() { storage.fill(0); length = 0; closed = true }
}
