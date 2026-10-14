package dev.elu.analytics.internal.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeExceptionObservationTest {
    @Test
    fun `type only observation never reads overridable throwable details`() {
        val throwable = HostileDetails()
        val observation = NativeExceptionObservation.from(throwable)

        assertEquals(HostileDetails::class.java.name, observation.type)
        assertFalse(observation.typeTruncated)
        assertEquals(NativeExceptionMechanism.JVM_UNCAUGHT, observation.mechanism)
        assertFalse(observation.handled)
        assertEquals(0, throwable.detailReads)
        assertFalse(observation.type.contains("PRIVATE_EXCEPTION_CANARY"))
        assertTrue(NativeExceptionObservation::class.java.declaredFields.none { it.type == Throwable::class.java || it.type == Thread::class.java })
    }

    @Test
    fun `long class name is bounded and explicitly marked truncated`() {
        val throwable = ExceptionWithLongTypeMetadataButNoCustomerDetailsWhoseBoundedFullyQualifiedNameIncludesThisTestPackageAndOuterClassAndThereforeExceedsTheTypeLimitWithoutExceedingAnIndividualClassFileNameLimit()
        val name = throwable.javaClass.name
        val observation = NativeExceptionObservation.from(throwable)

        assertTrue(name.length > NativeExceptionObservation.MAX_TYPE_UTF16_UNITS)
        assertEquals(name.take(NativeExceptionObservation.MAX_TYPE_UTF16_UNITS), observation.type)
        assertTrue(observation.typeTruncated)
        assertEquals(NativeExceptionMechanism.JVM_UNCAUGHT, observation.mechanism)
    }

    private class HostileDetails : Throwable("PRIVATE_EXCEPTION_CANARY") {
        var detailReads = 0

        private fun denied(): Nothing {
            detailReads += 1
            throw AssertionError("Throwable details were read")
        }

        override val message: String get() = denied()
        override val cause: Throwable get() = denied()

        override fun getLocalizedMessage(): String = denied()

        override fun getStackTrace(): Array<StackTraceElement> = denied()

        override fun toString(): String = denied()
    }

    private class ExceptionWithLongTypeMetadataButNoCustomerDetailsWhoseBoundedFullyQualifiedNameIncludesThisTestPackageAndOuterClassAndThereforeExceedsTheTypeLimitWithoutExceedingAnIndividualClassFileNameLimit : Throwable()
}
