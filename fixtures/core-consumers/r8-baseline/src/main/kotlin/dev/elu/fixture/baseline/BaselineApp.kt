package dev.elu.fixture.baseline

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.util.Log

/** The optimized consumer without the SDK: the size reference for the SDK's delta. */
class BaselineApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("EluConsumerBaseline", "started api=${Build.VERSION.SDK_INT}")
    }
}

class BaselineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("EluConsumerBaseline", "activity")
    }
}
