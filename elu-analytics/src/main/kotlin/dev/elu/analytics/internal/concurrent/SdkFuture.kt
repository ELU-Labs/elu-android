package dev.elu.analytics.internal.concurrent

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The asynchronous completion operations used inside the SDK, available on Android API 23. */
internal interface SdkCompletionStage<T> {
    fun whenComplete(action: (T?, Throwable?) -> Unit): SdkFuture<T>

    fun toFuture(): SdkFuture<T>
}

/**
 * A single-assignment result with synchronous completion listeners. It owns no executor and never
 * cancels the physical operation producing a result. Operation owners explicitly implement that
 * cancellation, or override [cancel] when original settlement must remain observable.
 *
 * Listeners always run outside the state lock. A listener's failure affects its dependent result,
 * never the original result or other listeners. Blocking [get] is for SDK worker lanes only.
 */
internal open class SdkFuture<T> : Future<T>, SdkCompletionStage<T> {
    private sealed interface Outcome<out T> {
        data class Value<T>(val value: T) : Outcome<T>

        data class Failure(val error: Throwable) : Outcome<Nothing>
    }

    private val lock = Any()
    private val ready = CountDownLatch(1)
    @Volatile private var outcome: Outcome<T>? = null
    private val listeners = ArrayList<(Outcome<T>) -> Unit>()

    fun complete(value: T): Boolean = settle(Outcome.Value(value))

    fun completeExceptionally(error: Throwable): Boolean = settle(Outcome.Failure(error))

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean =
        completeExceptionally(CancellationException()) || isCancelled

    override fun isCancelled(): Boolean = (outcome as? Outcome.Failure)?.error is CancellationException

    override fun isDone(): Boolean = outcome != null

    val isCompletedExceptionally: Boolean get() = outcome is Outcome.Failure

    override fun get(): T {
        if (outcome == null) ready.await()
        return report(checkNotNull(outcome))
    }

    override fun get(timeout: Long, unit: TimeUnit): T {
        if (outcome == null && !ready.await(timeout, unit)) throw TimeoutException()
        return report(checkNotNull(outcome))
    }

    override fun toFuture(): SdkFuture<T> = this

    override fun whenComplete(action: (T?, Throwable?) -> Unit): SdkFuture<T> {
        val dependent = SdkFuture<T>()
        observe { original ->
            val originalError = (original as? Outcome.Failure)?.error
            var callbackError: Throwable? = null
            try {
                action((original as? Outcome.Value)?.value, originalError)
            } catch (error: Throwable) {
                callbackError = error
            }
            when {
                originalError != null -> dependent.completeExceptionally(dependentFailure(originalError))
                callbackError != null -> dependent.completeExceptionally(dependentFailure(callbackError))
                original is Outcome.Value -> dependent.complete(original.value)
            }
        }
        return dependent
    }

    fun <U> thenCompose(action: (T) -> SdkCompletionStage<U>): SdkFuture<U> {
        val dependent = SdkFuture<U>()
        observe { original ->
            when (original) {
                is Outcome.Failure -> dependent.completeExceptionally(dependentFailure(original.error))
                is Outcome.Value -> {
                    try {
                        action(original.value).toFuture().observe { next ->
                            when (next) {
                                is Outcome.Value -> dependent.complete(next.value)
                                is Outcome.Failure -> dependent.completeExceptionally(dependentFailure(next.error))
                            }
                        }
                    } catch (error: Throwable) {
                        dependent.completeExceptionally(dependentFailure(error))
                    }
                }
            }
        }
        return dependent
    }

    private fun settle(value: Outcome<T>): Boolean {
        val callbacks: List<(Outcome<T>) -> Unit>
        synchronized(lock) {
            if (outcome != null) return false
            outcome = value
            callbacks = listeners.toList().asReversed()
            listeners.clear()
        }
        ready.countDown()
        callbacks.forEach { it(value) }
        return true
    }

    private fun observe(action: (Outcome<T>) -> Unit) {
        val current = synchronized(lock) {
            outcome.also { if (it == null) listeners.add(action) }
        }
        if (current != null) action(current)
    }

    private fun report(value: Outcome<T>): T = when (value) {
        is Outcome.Value -> value.value
        is Outcome.Failure -> {
            val error = value.error
            if (error is CancellationException) throw error
            throw ExecutionException(if (error is DependentFailure) error.cause else error)
        }
    }

    private class DependentFailure(cause: Throwable) : RuntimeException(cause)

    companion object {
        fun <T> completedFuture(value: T): SdkFuture<T> = SdkFuture<T>().apply { complete(value) }

        /** Joins every original result even if one has already failed or been cancelled. */
        fun allOf(vararg futures: SdkFuture<*>): SdkFuture<Unit> {
            val joined = SdkFuture<Unit>()
            if (futures.isEmpty()) return joined.apply { complete(Unit) }
            val joinLock = Any()
            var remaining = futures.size
            val failures = arrayOfNulls<Throwable>(futures.size)
            futures.forEachIndexed { index, future ->
                future.observe { original ->
                    var finished = false
                    var failure: Throwable? = null
                    synchronized(joinLock) {
                        failures[index] = (original as? Outcome.Failure)?.error
                        remaining -= 1
                        if (remaining == 0) {
                            finished = true
                            failure = failures.firstOrNull { it != null }
                        }
                    }
                    if (finished) {
                        val error = failure
                        if (error == null) joined.complete(Unit)
                        else joined.completeExceptionally(dependentFailure(error))
                    }
                }
            }
            return joined
        }

        private fun dependentFailure(error: Throwable): Throwable =
            if (error is DependentFailure) error else DependentFailure(error)
    }
}
