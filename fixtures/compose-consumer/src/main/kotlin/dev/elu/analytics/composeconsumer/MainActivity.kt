package dev.elu.analytics.composeconsumer

import android.app.Activity
import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import dev.elu.analytics.compose.EluAnnotatedReplayRoot
import dev.elu.analytics.compose.EluReplayBlock
import dev.elu.analytics.compose.EluReplayMask
import dev.elu.analytics.compose.rememberEluReplayPrivateRegion

/** Compile-only public API consumer; this fixture installs no recorder or transport. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply {
            setContent {
                val mask = rememberEluReplayPrivateRegion()
                val block = rememberEluReplayPrivateRegion()
                EluAnnotatedReplayRoot(requiredPrivateRegions = listOf(mask, block)) {
                    EluReplayMask(mask) {}
                    EluReplayBlock(block) {}
                }
            }
        })
    }
}
