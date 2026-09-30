package dev.elu.analytics

import android.view.View
import dev.elu.analytics.internal.replay.AnnotatedGeometryBinding
import dev.elu.analytics.internal.replay.AnnotatedRootRegistry

/** Identity-only privacy intent. Retain and declare it independently of conditional UI markers. */
public class EluReplayPrivateRegion private constructor() {
    public companion object {
        @JvmStatic public fun create(): EluReplayPrivateRegion = EluReplayPrivateRegion()
    }
}

/** Main-thread annotation binding only. This handle cannot capture, encode or submit an image. */
public class EluAnnotatedReplayBinding internal constructor(internal val entry: AnnotatedGeometryBinding) : AutoCloseable {
    public fun invalidate() { entry.invalidate() }
    public override fun close() { entry.close() }
}

/**
 * Declared geometry integration for one original View. Registration is available on API 23+;
 * the currently uninstalled capture path requires API 29+. No recorder or transport is installed.
 *
 * Every input/private region and unsupported paint must be declared and confined to its clipping
 * wrapper. A geometry reader is customer integration input, not automatic framework attestation.
 * It supplies no capture permission. All scope and binding methods must run on the main thread,
 * including preparation of the inactive scope.
 */
public class EluAnnotatedReplayRootScope private constructor(internal val registry: AnnotatedRootRegistry) : AutoCloseable {
    /** Attach only after the original UI commits. False means the registration is unusable. */
    public fun attach(): Boolean = registry.attach()
    public fun declareRequired(regions: List<EluReplayPrivateRegion>) { registry.declare(regions) }
    public fun bindRoot(reader: EluReplayGeometryReader): EluAnnotatedReplayBinding =
        EluAnnotatedReplayBinding(registry.bind(null, reader))
    public fun bindPrivate(region: EluReplayPrivateRegion, reader: EluReplayGeometryReader): EluAnnotatedReplayBinding =
        EluAnnotatedReplayBinding(registry.bind(region, reader))
    public override fun close() { registry.close() }

    public companion object {
        /** Pure preparation: does not change View tags, start a recorder or obtain SDK authority. */
        @JvmStatic public fun prepare(view: View): EluAnnotatedReplayRootScope {
            AnnotatedRootRegistry.main()
            return EluAnnotatedReplayRootScope(AnnotatedRootRegistry(view))
        }
    }
}
