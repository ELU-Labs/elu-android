package dev.elu.fixture.java;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import dev.elu.analytics.Elu;
import dev.elu.analytics.EluCaptureOptions;
import dev.elu.analytics.EluFeatureFlagSubscription;
import java.util.HashMap;
import java.util.Map;
import kotlin.Unit;

/** Compile-only public API consumer from Java; this fixture is never installed. */
public final class JavaConsumerActivity extends Activity {
    private EluFeatureFlagSubscription flags;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        View content = new View(this);
        setContentView(content);
        Map<String, Object> properties = new HashMap<>();
        properties.put("plan", "pro");
        Elu.capture("opened");
        Elu.capture("signed_up", properties);
        Elu.capture("upgraded", properties, new EluCaptureOptions(null, properties));
        Elu.screen("Home");
        Elu.identify("user-1");
        Elu.identify("user-1", properties, null);
        Elu.group("company", "acme");
        Elu.register(properties);
        Elu.registerOnce(properties);
        Elu.setPersonProperties(properties);
        Elu.maskView(content);
        Elu.blockView(content);
        Elu.captureException(new IllegalStateException("handled"));
        Object flag = Elu.getFeatureFlag("new-checkout");
        boolean enabled = Elu.isFeatureEnabled("new-checkout");
        flags = Elu.subscribeToFeatureFlags(snapshot -> { });
        Elu.reloadFeatureFlags();
        Elu.onFeatureFlagsLoaded(() -> Unit.INSTANCE);
        String distinctId = Elu.distinctId();
        if (flag == null && !enabled && distinctId == null && Elu.isOptedOut()) {
            Elu.optIn();
        }
        Elu.flush();
    }

    @Override
    protected void onDestroy() {
        flags.close();
        if (isFinishing()) {
            Elu.reset();
        }
        super.onDestroy();
    }
}
