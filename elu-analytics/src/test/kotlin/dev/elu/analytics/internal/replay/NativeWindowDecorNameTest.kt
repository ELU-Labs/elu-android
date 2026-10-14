package dev.elu.analytics.internal.replay

import org.junit.Assert.*
import org.junit.Test

class NativeWindowDecorNameTest {
    private val nestedDecor = "com.android.internal.policy.PhoneWindow\$DecorView"
    private val extractedDecor = "com.android.internal.policy.DecorView"

    @Test fun eachFrameworkDecorNameIsAcceptedOnlyOnTheLevelsThatDefineIt() {
        assertTrue(isFrameworkWindowDecorName(nestedDecor, 23))
        assertFalse(isFrameworkWindowDecorName(extractedDecor, 23))
        for (level in 24..36) {
            assertTrue("$extractedDecor@$level", isFrameworkWindowDecorName(extractedDecor, level))
            assertFalse("$nestedDecor@$level", isFrameworkWindowDecorName(nestedDecor, level))
        }
        assertFalse(isFrameworkWindowDecorName(nestedDecor, 22))
        assertFalse(isFrameworkWindowDecorName(extractedDecor, 22))
    }

    @Test fun nearbyRelocatedAndCustomerNamesRemainUnknownAncestors() {
        for (name in listOf(
            "com.android.internal.policy.impl.PhoneWindow\$DecorView", // API 22 and earlier
            "com.android.internal.policy.PhoneWindow",
            "com.android.internal.policy.PhoneWindow\$DecorView\$1",
            "com.android.internal.policy.DecorView\$1",
            "com.android.internal.policy.DecorViewSubclass",
            "com.android.internal.widget.DecorView",
            "example.com.android.internal.policy.DecorView",
            "com.android.internal.policy.decorview",
            "PhoneWindow\$DecorView", "DecorView", "",
        )) {
            for (level in 21..36) assertFalse("$name@$level", isFrameworkWindowDecorName(name, level))
        }
    }
}
