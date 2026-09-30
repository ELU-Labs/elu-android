package dev.elu.analytics.internal.replay

import org.junit.Assert.*
import org.junit.Test

/** Pure allocation/geometry controls; these do not establish renderer or device behavior. */
class NativeRasterScaleTest {
    @Test fun smallImagesKeepTheirOriginalPixelsAndViewport() {
        val size = AnnotatedRasterDimensions.fit(320, 640)
        assertEquals(320, size.viewportWidth); assertEquals(640, size.viewportHeight)
        assertEquals(320, size.imageWidth); assertEquals(640, size.imageHeight)
        assertEquals(AnnotatedRasterMask(0, 1, 11, 21), size.mask(.25, 1.75, 10.1, 20.25))
    }

    @Test fun fullHdAndOddViewportsPreserveDisplaySizeWithinBothImageCaps() {
        for ((width, height) in listOf(1080 to 1920, 1081 to 1921, 4097 to 2305, 16384 to 16384)) {
            val size = AnnotatedRasterDimensions.fit(width, height)
            assertEquals(width, size.viewportWidth); assertEquals(height, size.viewportHeight)
            assertTrue(size.imageWidth < width || size.imageHeight < height)
            assertTrue(size.imageWidth in 1..2048 && size.imageHeight in 1..2048)
            assertTrue(size.imageWidth.toLong() * size.imageHeight <= 1_048_576)
            // Floor rounding may differ by less than one output pixel per axis.
            assertTrue(kotlin.math.abs(size.imageWidth.toDouble() / width - size.imageHeight.toDouble() / height) <=
                1.0 / width + 1.0 / height)
        }
        val fullHd = AnnotatedRasterDimensions.fit(1080, 1920)
        assertEquals(768, fullHd.imageWidth); assertEquals(1365, fullHd.imageHeight)
        val square = AnnotatedRasterDimensions.fit(16384, 16384)
        assertEquals(1024, square.imageWidth); assertEquals(1024, square.imageHeight)
    }

    @Test fun extremeAspectRatiosNeverAllocateZeroOrExceedAnEdge() {
        for ((width, height) in listOf(1 to 1, 1 to 16384, 16384 to 1, 2048 to 512, 512 to 2048)) {
            val size = AnnotatedRasterDimensions.fit(width, height)
            assertTrue(size.imageWidth in 1..minOf(width, 2048))
            assertTrue(size.imageHeight in 1..minOf(height, 2048))
            assertTrue(size.imageWidth.toLong() * size.imageHeight <= 1_048_576)
        }
        assertEquals(2048, AnnotatedRasterDimensions.fit(16384, 1).imageWidth)
        assertEquals(1, AnnotatedRasterDimensions.fit(16384, 1).imageHeight)
    }

    @Test fun originalViewportLimitIsIndependentOfTheImageLimit() {
        for ((width, height) in listOf(0 to 1, 1 to 0, -1 to 1, 16385 to 1, 1 to 16385, Int.MAX_VALUE to 2)) {
            assertEquals("viewport-limit", assertThrows(IllegalStateException::class.java) {
                AnnotatedRasterDimensions.fit(width, height)
            }.message)
        }
    }

    @Test fun scaledFractionalMasksExpandOutwardInDevicePixelsIncludingSamplingMargin() {
        val size = AnnotatedRasterDimensions.fit(1080, 1920)
        assertEquals(AnnotatedRasterMask(6, 13, 23, 37), size.mask(10.25, 20.5, 30.75, 50.25))
        assertEquals(AnnotatedRasterMask(0, 0, 2, 2), size.mask(-10.0, -20.0, .25, .25))
        assertEquals(AnnotatedRasterMask(766, 1363, 768, 1365), size.mask(1079.75, 1919.75, 2000.0, 3000.0))
        assertEquals(AnnotatedRasterMask(0, 0, 768, 1365), size.mask(-10.0, -10.0, 2000.0, 3000.0))
        assertNull(size.mask(-10.0, 0.0, 0.0, 100.0))
        assertNull(size.mask(1080.0, 0.0, 1081.0, 100.0))
        assertNull(size.mask(1.0, 1.0, 1.0, 2.0))
    }

    @Test fun enclosingAndOverlappingRegionsCannotShrinkExclusion() {
        val size = AnnotatedRasterDimensions.fit(1081, 1921)
        val outer = checkNotNull(size.mask(.25, .75, 800.25, 1600.75))
        for (i in 1..50) {
            val inner = checkNotNull(size.mask(i * .5, i * .75, 100.25 + i, 200.75 + i))
            assertTrue(outer.left <= inner.left && outer.top <= inner.top)
            assertTrue(outer.right >= inner.right && outer.bottom >= inner.bottom)
        }
        val a = checkNotNull(size.mask(10.25, 10.75, 50.25, 50.75))
        val b = checkNotNull(size.mask(49.75, 49.75, 90.25, 90.75))
        assertTrue(a.right > b.left && a.bottom > b.top)
    }

    @Test fun malformedMaskCoordinatesRefuseRatherThanOverflowOrDisappear() {
        val size = AnnotatedRasterDimensions.fit(1080, 1920)
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalStateException::class.java) { size.mask(bad, 0.0, 1.0, 1.0) }
        }
        assertThrows(IllegalStateException::class.java) { size.mask(2.0, 0.0, 1.0, 1.0) }
        assertThrows(IllegalStateException::class.java) { size.mask(0.0, 2.0, 1.0, 1.0) }
    }

    @Test fun identicalRoundedImagesDoNotReplaceTheOriginalViewport() {
        val first = AnnotatedRasterDimensions.fit(16383, 1)
        val second = AnnotatedRasterDimensions.fit(16384, 1)
        assertEquals(first.imageWidth, second.imageWidth); assertEquals(first.imageHeight, second.imageHeight)
        assertNotEquals(first.viewportWidth, second.viewportWidth)
        assertNotEquals(first, second)
    }
}
