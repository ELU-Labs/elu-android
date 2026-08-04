# ELU Analytics consumer rules.
# The SDK facade uses no reflection; the resolved runtime dependency ships its own
# consumer rules inside its AAR, so R8 in the host app already keeps its
# implementation. The additional keeps below pin
# the public facade surface so aggressive shrinker configs cannot strip or
# rename the entry points customers call.
-keep class dev.elu.analytics.Elu { public *; }
-keep class dev.elu.analytics.EluOptions { public *; }
