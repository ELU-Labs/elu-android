package dev.elu.fixture.smoke

import dev.elu.analytics.EluOkHttpInterceptor
import java.net.InetAddress
import java.net.ServerSocket
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Request

/** An app that already uses OkHttp 5 adds the SDK's interceptor to its own client. */
internal object SmokeExtras {
    fun run(): String = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
        val responder = Thread({
            server.accept().use { socket ->
                val request = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                while (request.readLine()?.isNotEmpty() == true) Unit
                socket.getOutputStream().apply {
                    write(RESPONSE.toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
            }
        }, "elu-consumer-smoke-loopback").apply { start() }
        val client = OkHttpClient.Builder().addInterceptor(EluOkHttpInterceptor()).build()
        try {
            val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/consumer-smoke").build())
            val body = call.execute().use { response ->
                check(response.code == 200) { "unexpected status ${response.code}" }
                response.body.string()
            }
            check(body == "ok") { "the interceptor must return the original response body" }
            responder.join(10_000)
            "okhttp=${OkHttp.VERSION} interceptorStatus=200"
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private const val RESPONSE =
        "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
}
