# ELU Analytics consumer rules.
# Keep the public facade surface so aggressive shrinker configurations cannot
# strip or rename the entry points customers call. ELU's runtime uses no reflection.
-keep class dev.elu.analytics.Elu { public *; }
-keep class dev.elu.analytics.EluOptions { public *; }
