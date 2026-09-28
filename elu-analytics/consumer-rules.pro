# ELU Analytics consumer rules.
# Keep the public facade surface so aggressive shrinker configurations cannot
# strip or rename the entry points customers call. ELU's runtime uses no reflection.
-keep class dev.elu.analytics.Elu { public *; }
-keep class dev.elu.analytics.EluOptions { public *; }

# Optional AppCompat replay recognition uses exact class names and immediate
# framework superclasses. Keep these class identities if the app uses them;
# unused classes may still be removed. This does not add an AppCompat dependency.
-keepnames class androidx.appcompat.widget.AppCompatTextView
-keepnames class androidx.appcompat.widget.AppCompatButton
-keepnames class androidx.appcompat.widget.AppCompatCheckBox
-keepnames class androidx.appcompat.widget.AppCompatRadioButton
-keepnames class androidx.appcompat.widget.AppCompatToggleButton
-keepnames class androidx.appcompat.widget.AppCompatCheckedTextView
-keepnames class androidx.appcompat.widget.LinearLayoutCompat
-keepnames class androidx.appcompat.widget.ContentFrameLayout
-keepnames class androidx.appcompat.widget.FitWindowsLinearLayout
-keepnames class androidx.appcompat.widget.FitWindowsFrameLayout
-keepnames class androidx.appcompat.widget.ActionBarOverlayLayout
