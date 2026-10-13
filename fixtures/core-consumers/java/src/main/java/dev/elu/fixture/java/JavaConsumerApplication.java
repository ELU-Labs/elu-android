package dev.elu.fixture.java;

import android.app.Application;
import dev.elu.analytics.Elu;
import dev.elu.analytics.EluDiagnosticsOptions;
import dev.elu.analytics.EluOptions;
import java.util.Collections;

/** Setup from Java exactly as the README shows it for Kotlin. */
public final class JavaConsumerApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        EluOptions options = new EluOptions(new EluDiagnosticsOptions(true));
        options.setPropertyDenylist(Collections.singletonList("password"));
        options.setBeforeSend(event -> event.getEvent().startsWith("internal_") ? null : event);
        Elu.setup(this, "YOUR_SITE_KEY", options);
    }
}
