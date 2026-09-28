package dev.elu.analytics.internal.replay

import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CheckedTextView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.ToggleButton

/**
 * Optional AppCompat recognition, without loading a class or invoking an AppCompat API.
 * Both the exact name and immediate framework superclass must match. Custom subclasses,
 * relocated classes and a changed hierarchy remain opaque. Consumer rules preserve these
 * names; an actual optimized consumer is still a release qualification requirement.
 */
internal object NativeAppCompatViewTypes {
    private const val PREFIX = "androidx.appcompat.widget."

    fun isText(name: String, superclass: Class<*>?): Boolean = when (name) {
        PREFIX + "AppCompatTextView" -> superclass === TextView::class.java
        PREFIX + "AppCompatButton" -> superclass === Button::class.java
        PREFIX + "AppCompatCheckBox" -> superclass === CheckBox::class.java
        PREFIX + "AppCompatRadioButton" -> superclass === RadioButton::class.java
        PREFIX + "AppCompatToggleButton" -> superclass === ToggleButton::class.java
        PREFIX + "AppCompatCheckedTextView" -> superclass === CheckedTextView::class.java
        else -> false
    }

    fun isContainer(name: String, superclass: Class<*>?): Boolean = when (name) {
        PREFIX + "LinearLayoutCompat" -> superclass === ViewGroup::class.java
        PREFIX + "ContentFrameLayout", PREFIX + "FitWindowsFrameLayout" -> superclass === FrameLayout::class.java
        PREFIX + "FitWindowsLinearLayout" -> superclass === LinearLayout::class.java
        else -> false
    }

    fun isContentFrame(name: String, superclass: Class<*>?): Boolean =
        name == PREFIX + "ContentFrameLayout" && superclass === FrameLayout::class.java

    // Decoration is ancestry-only. Never traverse its toolbar siblings as application content.
    fun isActionBar(name: String, superclass: Class<*>?): Boolean =
        name == PREFIX + "ActionBarOverlayLayout" && superclass === ViewGroup::class.java
}
