package dev.elu.analytics.upgradeevidence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.elu.analytics.Elu
import dev.elu.analytics.EluOptions
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpgradeContinuityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val evidence = context.getSharedPreferences(EVIDENCE_PREFERENCES, Context.MODE_PRIVATE)

    @Test
    fun establishPublishedAnonymousState() {
        assertFalse("upgrade evidence requires a fresh install", evidence.contains(KEY_ANONYMOUS_DIGEST))

        ConfigServer().use { server ->
            Elu.setup(context, SITE_KEY, EluOptions(configHost = server.baseUrl))
            val anonymousId = awaitDistinctId { it != IDENTIFIED_ID }
            assertTrue("published SDK must establish anonymous identity", anonymousId.isNotBlank())
            assertTrue("config request was not observed", server.awaitRequest())

            assertTrue(
                "anonymous continuity digest could not be persisted",
                evidence.edit()
                    .putString(KEY_ANONYMOUS_DIGEST, sha256(anonymousId))
                    .commit(),
            )
            allowAsyncIdentityPersistence()
        }
    }

    @Test
    fun verifyPublishedAnonymousRehydration() {
        val anonymousDigest = evidence.getString(KEY_ANONYMOUS_DIGEST, null)
        assertNotNull("anonymous identity evidence was unavailable for published rehydration", anonymousDigest)
        assertTrue("anonymous identity digest is malformed", anonymousDigest!!.matches(SHA256_PATTERN))

        ConfigServer().use { server ->
            Elu.setup(context, SITE_KEY, EluOptions(configHost = server.baseUrl))
            assertEquals(
                "published SDK did not rehydrate the anonymous identity",
                anonymousDigest,
                sha256(awaitDistinctId { sha256(it) == anonymousDigest }),
            )
            assertTrue("config request was not observed", server.awaitRequest())
        }
    }

    @Test
    fun verifyAnonymousReplacementContinuity() {
        val anonymousDigest = evidence.getString(KEY_ANONYMOUS_DIGEST, null)
        assertNotNull("anonymous identity evidence did not survive replacement install", anonymousDigest)
        assertTrue("anonymous identity digest is malformed", anonymousDigest!!.matches(SHA256_PATTERN))

        ConfigServer().use { server ->
            Elu.setup(context, SITE_KEY, EluOptions(configHost = server.baseUrl))
            assertEquals(
                "candidate SDK did not continue the anonymous identity",
                anonymousDigest,
                sha256(awaitDistinctId { sha256(it) == anonymousDigest }),
            )
            assertTrue("config request was not observed", server.awaitRequest())
        }
    }

    @Test
    fun establishPublishedIdentifiedState() {
        assertFalse("upgrade evidence requires a fresh install", evidence.contains(KEY_IDENTIFIED_ID))

        ConfigServer().use { server ->
            Elu.setup(context, SITE_KEY, EluOptions(configHost = server.baseUrl))
            assertTrue(
                "published SDK must establish anonymous identity before identification",
                awaitDistinctId { it != IDENTIFIED_ID }.isNotBlank(),
            )
            Elu.identify(IDENTIFIED_ID, mapOf("upgrade_evidence" to true))
            assertEquals(
                "published SDK must establish identified identity",
                IDENTIFIED_ID,
                awaitDistinctId { it == IDENTIFIED_ID },
            )
            assertTrue("config request was not observed", server.awaitRequest())
            assertTrue(
                "identified continuity value could not be persisted",
                evidence.edit().putString(KEY_IDENTIFIED_ID, IDENTIFIED_ID).commit(),
            )
            allowAsyncIdentityPersistence()
        }
    }

    @Test
    fun verifyPublishedIdentifiedRehydration() {
        assertEquals(
            "identified identity evidence was unavailable for published rehydration",
            IDENTIFIED_ID,
            evidence.getString(KEY_IDENTIFIED_ID, null),
        )

        ConfigServer().use { server ->
            Elu.setup(context, SITE_KEY, EluOptions(configHost = server.baseUrl))
            assertEquals(
                "published SDK did not rehydrate the identified identity",
                IDENTIFIED_ID,
                awaitDistinctId { it == IDENTIFIED_ID },
            )
            assertTrue("config request was not observed", server.awaitRequest())
        }
    }

    @Test
    fun verifyIdentifiedReplacementContinuity() {
        assertEquals(
            "identified identity evidence did not survive replacement install",
            IDENTIFIED_ID,
            evidence.getString(KEY_IDENTIFIED_ID, null),
        )

        ConfigServer().use { server ->
            Elu.setup(context, SITE_KEY, EluOptions(configHost = server.baseUrl))
            assertEquals(
                "candidate SDK did not continue the identified identity",
                IDENTIFIED_ID,
                awaitDistinctId { it == IDENTIFIED_ID },
            )
            assertTrue("config request was not observed", server.awaitRequest())
        }
    }

    private fun awaitDistinctId(predicate: (String) -> Boolean): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var observed: String? = null
        while (System.nanoTime() < deadline) {
            observed = Elu.distinctId()
            if (observed != null && predicate(observed)) return observed
            Thread.sleep(50)
        }
        assertNotNull("SDK did not expose identity before timeout", observed)
        return observed!!
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun allowAsyncIdentityPersistence() {
        // The public facade has no persistence acknowledgement. This delay is
        // not treated as evidence: the next fresh application start must rehydrate
        // and assert the identity before candidate replacement is allowed.
        Thread.sleep(ASYNC_PERSISTENCE_SETTLE_MS)
    }

    private class ConfigServer : Closeable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val requestSeen = CountDownLatch(1)
        private val worker =
            thread(name = "elu-upgrade-config", isDaemon = true) {
                try {
                    socket.accept().use { client ->
                        val input = client.getInputStream().bufferedReader()
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val body = CONFIG_BODY.toByteArray(Charsets.UTF_8)
                        client.getOutputStream().use { output ->
                            output.write(
                                ("HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: ${body.size}\r\n" +
                                    "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
                            )
                            output.write(body)
                            output.flush()
                        }
                        requestSeen.countDown()
                    }
                } catch (_: Throwable) {
                    // The assertion below reports a missing request; close() also lands here.
                }
            }

        val baseUrl: String = "http://127.0.0.1:${socket.localPort}"

        fun awaitRequest(): Boolean = requestSeen.await(15, TimeUnit.SECONDS)

        override fun close() {
            socket.close()
            worker.join(TimeUnit.SECONDS.toMillis(1))
        }
    }

    private companion object {
        const val SITE_KEY = "upgrade-evidence"
        const val IDENTIFIED_ID = "upgrade-evidence-user"
        const val EVIDENCE_PREFERENCES = "dev.elu.analytics.upgrade-evidence"
        const val KEY_ANONYMOUS_DIGEST = "anonymousIdentitySha256"
        const val KEY_IDENTIFIED_ID = "identifiedIdentity"
        const val ASYNC_PERSISTENCE_SETTLE_MS = 1_000L
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        val CONFIG_BODY =
            """
            {
              "v": 1,
              "enabled": true,
              "publicToken": "upgrade-evidence-token",
              "host": "http://127.0.0.1:1",
              "privacy": {
                "blockEu": false,
                "maskTextInputs": true,
                "maskAllText": false,
                "maskImages": false,
                "replayNewUsersOnly": false,
                "replayMaxMinutes": 0
              }
            }
            """.trimIndent()
    }
}
