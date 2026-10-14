package dev.elu.analytics.internal.replay

import android.view.ViewGroup
import android.widget.*
import org.junit.Assert.*
import org.junit.Test

class NativeAppCompatViewTypesTest {
    @Test fun onlyExactNamedLeavesWithTheirFrameworkBaseAreAdmitted() {
        val bases = mapOf("AppCompatTextView" to TextView::class.java,
            "AppCompatButton" to Button::class.java, "AppCompatCheckBox" to CheckBox::class.java,
            "AppCompatRadioButton" to RadioButton::class.java, "AppCompatToggleButton" to ToggleButton::class.java,
            "AppCompatCheckedTextView" to CheckedTextView::class.java)
        for ((name, base) in bases) {
            val full = "androidx.appcompat.widget.$name"
            assertTrue(NativeAppCompatViewTypes.isText(full, base))
            assertFalse(NativeAppCompatViewTypes.isText(full, EditText::class.java))
            assertFalse(NativeAppCompatViewTypes.isText(full, null))
            assertFalse(NativeAppCompatViewTypes.isText("example.$name", base))
            assertFalse(NativeAppCompatViewTypes.isText(full + "Subclass", base))
            assertFalse(NativeAppCompatViewTypes.isContainer(full, base))
        }
    }

    @Test fun inputsMaterialWidgetsAndCustomClassesCannotClaimReadableText() {
        for (name in listOf("androidx.appcompat.widget.AppCompatEditText", "androidx.appcompat.widget.AppCompatAutoCompleteTextView",
            "androidx.appcompat.widget.AppCompatMultiAutoCompleteTextView", "androidx.appcompat.widget.SwitchCompat",
            "com.google.android.material.textview.MaterialTextView", "example.CustomerTextView")) {
            assertFalse(NativeAppCompatViewTypes.isText(name, TextView::class.java))
        }
    }

    @Test fun containersHaveClosedHierarchyAndActionBarIsAncestryOnly() {
        val bases = mapOf("LinearLayoutCompat" to ViewGroup::class.java,
            "ContentFrameLayout" to FrameLayout::class.java, "FitWindowsFrameLayout" to FrameLayout::class.java,
            "FitWindowsLinearLayout" to LinearLayout::class.java)
        for ((name, base) in bases) {
            val full = "androidx.appcompat.widget.$name"
            assertTrue(NativeAppCompatViewTypes.isContainer(full, base))
            assertFalse(NativeAppCompatViewTypes.isContainer(full, TextView::class.java))
            assertFalse(NativeAppCompatViewTypes.isContainer(full + "Subclass", base))
        }
        val actionBar = "androidx.appcompat.widget.ActionBarOverlayLayout"
        assertTrue(NativeAppCompatViewTypes.isActionBar(actionBar, ViewGroup::class.java))
        assertFalse(NativeAppCompatViewTypes.isContainer(actionBar, ViewGroup::class.java))
        assertFalse(NativeAppCompatViewTypes.isActionBar(actionBar, FrameLayout::class.java))
        assertTrue(NativeAppCompatViewTypes.isContentFrame("androidx.appcompat.widget.ContentFrameLayout", FrameLayout::class.java))
        assertFalse(NativeAppCompatViewTypes.isContentFrame("androidx.appcompat.widget.FitWindowsFrameLayout", FrameLayout::class.java))
    }
}
