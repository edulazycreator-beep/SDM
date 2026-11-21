package com.sdm.downloadmanager.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.sdm.core.designsystem.theme.LocalAnimationsEnabled
import com.sdm.downloadmanager.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The logo background: logo blue at the top right fading to navy at the bottom left. */
val BrandGradient = Brush.linearGradient(
    0f to Color(0xFF3A60A3), 0.55f to Color(0xFF2F5292), 1f to Color(0xFF10264D),
    start = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, 0f),
    end = androidx.compose.ui.geometry.Offset(0f, Float.POSITIVE_INFINITY),
)

/** Classic bounce: the arrow lands, hops twice and settles. */
val BounceEasing = Easing { t ->
    val n = 7.5625f; val d = 2.75f
    when {
        t < 1 / d -> n * t * t
        t < 2 / d -> { val x = t - 1.5f / d; n * x * x + 0.75f }
        t < 2.5f / d -> { val x = t - 2.25f / d; n * x * x + 0.9375f }
        else -> { val x = t - 2.625f / d; n * x * x + 0.984375f }
    }
}

/**
 * The SDM wordmark with "Smart Download Manager". When animated it grows in with a soft spring,
 * the download arrow drops into the D, then keeps nudging down gently like a download hint.
 */
@Composable
fun BrandLogo(modifier: Modifier = Modifier, animate: Boolean = true, tint: Color = Color.White) {
    val anims = LocalAnimationsEnabled.current && animate
    val appear = remember { Animatable(if (anims) 0f else 1f) }
    val drop = remember { Animatable(if (anims) 0f else 1f) }
    LaunchedEffect(anims) {
        if (!anims) return@LaunchedEffect
        launch { appear.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 180f)) }
        delay(280)
        drop.animateTo(1f, tween(700, easing = BounceEasing))
    }
    val nudge by if (anims) rememberInfiniteTransition(label = "nudge").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "nudge",
    ) else remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val ratio = 770f / 310f
    Box(
        modifier
            .semantics { contentDescription = "SDM – Smart Download Manager" }
            .graphicsLayer {
                val v = appear.value
                scaleX = 0.72f + 0.28f * v; scaleY = scaleX
                alpha = v.coerceIn(0f, 1f)
            },
    ) {
        Image(painterResource(R.drawable.logo_body), null, Modifier.fillMaxWidth().aspectRatio(ratio), colorFilter = ColorFilter.tint(tint))
        Image(
            painterResource(R.drawable.logo_arrow), null,
            Modifier.fillMaxWidth().aspectRatio(ratio).graphicsLayer {
                val d = drop.value
                val settled = d >= 1f && !drop.isRunning
                translationY = -(1f - d) * size.height * 0.55f + if (settled) nudge * size.height * 0.025f else 0f
                alpha = (d * 4f).coerceIn(0f, 1f)
            },
            colorFilter = ColorFilter.tint(tint),
        )
    }
}
