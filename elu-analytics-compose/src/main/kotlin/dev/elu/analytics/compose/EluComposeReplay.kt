package dev.elu.analytics.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalView
import dev.elu.analytics.EluAnnotatedReplayRootScope
import dev.elu.analytics.EluReplayPrivateRegion
import dev.elu.analytics.compose.internal.replayRegion

@Composable
public fun rememberEluReplayPrivateRegion(): EluReplayPrivateRegion = remember { EluReplayPrivateRegion.create() }

internal val LocalReplayScope = staticCompositionLocalOf<EluAnnotatedReplayRootScope?> { null }

/**
 * Explicit annotation boundary for the original mounted content. Every input and private region
 * requires an ELU mask/block wrapper. Unregistered paint is not automatically classified.
 *
 * This module currently installs no recorder or transport. Annotations alone grant no collection.
 * Unsupported overlays, recorded layers and effects require a supported enclosing block or
 * suspension. Every private drawing operation must remain confined to its supplied wrapper.
 * Another window, popup or separate rendering surface is outside this root. This is not the
 * automatic Views masking profile. Annotation registration supports API 23+; actual replay capture still requires API 29+.
 */
@Composable
public fun EluAnnotatedReplayRoot(
    requiredPrivateRegions: List<EluReplayPrivateRegion>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val registry = remember(view) { EluAnnotatedReplayRootScope.prepare(view) }
    SideEffect { registry.declareRequired(requiredPrivateRegions) }
    DisposableEffect(registry, view) {
        registry.attach()
        onDispose { registry.close() }
    }
    CompositionLocalProvider(LocalReplayScope provides registry) {
        Box(modifier.clipToBounds().replayRegion(registry, null)) { content() }
    }
}

/** Clips the entire supplied subtree. A missing binding never retires its required intent. */
@Composable
public fun EluReplayMask(region: EluReplayPrivateRegion, content: @Composable () -> Unit) {
    PrivateRegion(region, content)
}

/** Same privacy exclusion as a mask; no private text, image or descendant is inspected. */
@Composable
public fun EluReplayBlock(region: EluReplayPrivateRegion, content: @Composable () -> Unit) {
    PrivateRegion(region, content)
}

@Composable
private fun PrivateRegion(region: EluReplayPrivateRegion, content: @Composable () -> Unit) {
    val registry = LocalReplayScope.current
    // Outside an annotated root this is still an ordinary clipped original subtree; no capture.
    val binding = if (registry == null) Modifier else Modifier.replayRegion(registry, region)
    Box(Modifier.clipToBounds().then(binding)) { content() }
}
