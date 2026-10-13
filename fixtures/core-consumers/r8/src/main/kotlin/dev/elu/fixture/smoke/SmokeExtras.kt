package dev.elu.fixture.smoke

/** The minimal optimized app adds no networking of its own; OkHttp arrives only through the SDK. */
internal object SmokeExtras {
    fun run(): String = "okhttp=sdk-only"
}
