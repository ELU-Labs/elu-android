package dev.elu.analytics

import dev.elu.analytics.internal.network.NativeNetworkInterceptor
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Explicit opt-in request metrics for a customer's OkHttp client. Install with
 * `builder.addInterceptor(EluOkHttpInterceptor())`; ELU never patches clients globally.
 * Reports method, status, time to response headers and a failure Boolean only.
 * No URL, headers, bodies or exception messages are captured. Original responses
 * and failures pass through unchanged, including caller ownership of response bodies.
 */
public class EluOkHttpInterceptor : Interceptor {
    private val delegate = NativeNetworkInterceptor(Elu::beginNetworkObservation)

    override fun intercept(chain: Interceptor.Chain): Response = delegate.intercept(chain)
}
