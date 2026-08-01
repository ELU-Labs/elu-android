# ELU Analytics consumer rules.
# The wrapper itself uses no reflection; posthog-android ships its own consumer
# rules (Gson model keeps etc.) inside its AAR, so R8 in the host app already
# keeps what the underlying SDK needs. The keeps below are defensive: they pin
# the public facade surface so aggressive shrinker configs cannot strip or
# rename the entry points customers call.
-keep class dev.elu.analytics.Elu { public *; }
-keep class dev.elu.analytics.EluOptions { public *; }
