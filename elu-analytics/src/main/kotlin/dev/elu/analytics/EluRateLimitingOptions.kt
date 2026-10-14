package dev.elu.analytics

/** Local capture budget shared by event categories; identity mutations and replay are exempt. */
public class EluRateLimitingOptions @JvmOverloads constructor(
    eventsPerSecond: Double = 10.0,
    eventsBurstLimit: Double? = null,
) {
    public val eventsPerSecond: Double = eventsPerSecond.takeIf { it.isFinite() && it > 0.0 } ?: 10.0
    public val eventsBurstLimit: Double = maxOf(this.eventsPerSecond,
        eventsBurstLimit?.takeIf { it.isFinite() && it > 0.0 }
            ?: (this.eventsPerSecond * 10.0).let { if (it.isFinite()) it else Double.MAX_VALUE })
}
