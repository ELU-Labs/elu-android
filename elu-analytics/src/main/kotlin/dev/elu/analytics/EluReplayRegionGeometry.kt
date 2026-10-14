package dev.elu.analytics

/**
 * Fresh declared geometry, consumed only on main during one capture pass. Identity fields are
 * compared by reference only; no text or properties are read from them. Coordinates are physical
 * pixels in the original host's window. Neither these facts nor a reader grant capture authority.
 */
public class EluReplayRegionGeometry(
    public val coordinateIdentity: Any,
    public val parentIdentity: Any?,
    public val generation: Long,
    public val topLeftX: Float,
    public val topLeftY: Float,
    public val topRightX: Float,
    public val topRightY: Float,
    public val bottomLeftX: Float,
    public val bottomLeftY: Float,
    public val bottomRightX: Float,
    public val bottomRightY: Float,
)

/**
 * Synchronous main-thread read of the original mounted geometry. Return null on attachment,
 * parent, identity or layout uncertainty. Do not copy text, paint or reconstruct another UI.
 * The SDK checks its deadline before and after this call; it cannot preempt arbitrary app code.
 */
public fun interface EluReplayGeometryReader {
    public fun read(): EluReplayRegionGeometry?
}
