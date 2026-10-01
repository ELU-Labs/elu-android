package dev.elu.analytics.internal.diagnostics

import dev.elu.analytics.internal.concurrent.SdkFuture
import dev.elu.analytics.internal.runtime.RuntimeDiagnosticsClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A single bounded foreground observation. Never retries delivery or installs app callbacks. */
internal class NativeStartupMonitor(
    private val process: NativeStartupProcess,
    private val clock: RuntimeDiagnosticsClock,
    private val records: () -> List<NativeStartupRecord>?,
    private val context: () -> NativeStartupContext?,
    private val emit: (NativeStartupContext, NativeStartupMeasurement) -> Unit,
    private val deliveryReady: () -> Boolean = { true },
) {
    private val lock = Any()
    private val worker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "elu-startup").apply { isDaemon = true } }
    private var started = false
    private var foreground = false
    private var closed = false
    private var attempts = 0
    private var original: NativeStartupContext? = null
    private var observation: NativeStartupObservation? = null
    private var pending: NativeStartupMeasurement? = null
    private var began: dev.elu.analytics.internal.runtime.RuntimeDiagnosticsClockReading? = null
    private var last: dev.elu.analytics.internal.runtime.RuntimeDiagnosticsClockReading? = null
    private val closeResult = object : SdkFuture<Unit>() { override fun cancel(mayInterruptIfRunning: Boolean) = false }

    fun foreground(active: Boolean) = synchronized(lock) {
        if (closed) return@synchronized
        foreground = active
        if (!active && started) { pending = null; observation?.cancel(); worker.shutdownNow(); return@synchronized }
        if (active && !started) {
            started = true
            worker.scheduleWithFixedDelay({ tick() }, 0, 100, TimeUnit.MILLISECONDS)
        }
    }

    private fun tick() {
        try {
            val admitted = synchronized(lock) {
                if (closed || !foreground || ++attempts > NativeStartupObservation.MAXIMUM_POLLS) {
                    worker.shutdown(); return
                }
                context().also { now ->
                    if (original != null && now != original) { observation?.cancel(); worker.shutdown(); return }
                }
            } ?: return
            if (synchronized(lock) { original == null }) {
                // BOOT_COUNT can use a binder call too. Never hold the lifecycle
                // lock around any OS query; withdrawal must not wait for it.
                val time = clock.read() ?: run { worker.shutdown(); return }
                synchronized(lock) {
                    if (closed || !foreground || context() != admitted) { worker.shutdown(); return }
                    original = admitted
                    began = time; last = time
                    observation = NativeStartupObservation(process, admitted.epoch, time)
                }
            }
            val queued = synchronized(lock) { pending }
            // The binder query runs without the lifecycle lock. Publication rechecks withdrawal.
            val rows = if (queued == null) records() ?: run { worker.shutdown(); return } else emptyList()
            val now = clock.read() ?: run { worker.shutdown(); return }
            synchronized(lock) {
                if (closed || !foreground || context() != admitted) { observation?.cancel(); worker.shutdown(); return }
                val start = began ?: return
                val previous = last ?: return
                if (!now.continues(admitted.epoch) || now.wallMillis < previous.wallMillis ||
                    now.uptimeNanos < previous.uptimeNanos || now.elapsedNanos < previous.elapsedNanos ||
                    now.elapsedNanos - start.elapsedNanos > NativeStartupObservation.MAXIMUM_OBSERVATION_NANOS) {
                    pending = null; worker.shutdown(); return
                }
                last = now
                val current = observation ?: return
                val measurement = queued ?: current.observe(rows, now, admitted.epoch, foreground)
                if (measurement == null && current.finished) worker.shutdown()
                if (measurement != null) {
                    pending = measurement
                    if (deliveryReady()) { pending = null; worker.shutdown(); emit(admitted, measurement) }
                }
            }
        } catch (_: Exception) { synchronized(lock) { observation?.cancel(); worker.shutdown() } }
    }

    fun closeAndWait(): SdkFuture<Unit> = synchronized(lock) {
        if (closed) return closeResult
        closed = true; foreground = false; pending = null; observation?.cancel(); worker.shutdownNow()
        Thread({
            try {
                check(worker.awaitTermination(5, TimeUnit.SECONDS)) { "Startup observation did not settle" }
                closeResult.complete(Unit)
            } catch (error: Throwable) { closeResult.completeExceptionally(error) }
        }, "elu-startup-close").apply { isDaemon = true }.start()
        closeResult
    }
}
