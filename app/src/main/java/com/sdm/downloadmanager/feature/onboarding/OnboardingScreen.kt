package com.sdm.downloadmanager.feature.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.sdm.core.designsystem.theme.LocalAnimationsEnabled
import com.sdm.downloadmanager.ui.BrandGradient
import com.sdm.downloadmanager.ui.BrandLogo
import kotlin.math.abs
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material3.Button
import com.sdm.core.designsystem.i18n.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.sdm.core.designsystem.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private data class Page(val icon: ImageVector, val title: String, val body: String)

private val pages = listOf(
    Page(Icons.Outlined.Download, "Welcome to SDM", "Download Smarter. Faster. Better.\n\nSDM splits large files into parts and downloads them in parallel when the server allows it, pauses and resumes safely, and checks every file before calling it complete."),
    Page(Icons.Outlined.Notifications, "Stay informed", "SDM shows progress, speed and remaining time in notifications, with pause and cancel buttons. Android asks for permission to show them.\n\nWithout it, downloads still run; you will see them in the system's task manager instead."),
    Page(Icons.Outlined.Bolt, "Works in the background", "Android limits background work to save battery. SDM uses the system's long-running download jobs, so transfers keep going when you switch apps. If your phone stops them, open SDM and they continue from where they stopped."),
    Page(Icons.Outlined.TipsAndUpdates, "Quick start", "• Copy a link: SDM offers to download it when you open the app.\n• Use the built-in browser: downloads carry your sign-in cookies.\n• Share a link to SDM from any app.\n• Set rules and schedules in Settings."),
)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val anims = LocalAnimationsEnabled.current
    val index = pager.currentPage
    fun next() = scope.launch { if (anims) pager.animateScrollToPage(index + 1, animationSpec = tween(520, easing = FastOutSlowInEasing)) else pager.scrollToPage(index + 1) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { next() }
    val asksPermission = index == 1 && Build.VERSION.SDK_INT >= 33
    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val heroHeight = maxHeight * 0.46f
            // Brand panel behind the pictures; its corner softens as you move through the pages.
            val progress = pager.currentPage + pager.currentPageOffsetFraction
            Box(
                Modifier.fillMaxWidth().height(heroHeight)
                    .clip(RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp))
                    .background(BrandGradient),
            ) {
                // Two soft light circles drift with the pages for depth.
                Box(Modifier.size(220.dp).graphicsLayer { translationX = size.width * (1.1f - progress * 0.25f); translationY = -size.height * 0.25f }
                    .background(Color.White.copy(alpha = 0.07f), CircleShape))
                Box(Modifier.size(140.dp).graphicsLayer { translationX = -size.width * (0.2f - progress * 0.18f); translationY = heroHeight.toPx() - size.height * 0.7f }
                    .background(Color.White.copy(alpha = 0.06f), CircleShape))
            }
            Column(Modifier.fillMaxSize()) {
                HorizontalPager(pager, Modifier.weight(1f)) { page ->
                    val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction)
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().height(heroHeight).statusBarsPadding(), contentAlignment = Alignment.Center) {
                            Box(Modifier.graphicsLayer {
                                // Pictures move slower than the page (parallax) and shrink a little as they leave.
                                translationX = offset * size.width * 0.45f
                                val f = 1f - abs(offset).coerceIn(0f, 1f) * 0.25f
                                scaleX = f; scaleY = f
                                alpha = 1f - abs(offset).coerceIn(0f, 1f)
                            }) {
                                if (page == 0) BrandLogo(Modifier.fillMaxWidth(0.72f), animate = true)
                                else FloatingIcon(pages[page].icon, anims)
                            }
                        }
                        Column(
                            Modifier.padding(horizontal = 28.dp).padding(top = 28.dp).graphicsLayer {
                                translationX = offset * size.width * 0.2f
                                alpha = 1f - abs(offset).coerceIn(0f, 1f) * 1.4f
                            },
                        ) {
                            Text(pages[page].title, style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.height(10.dp))
                            Text(pages[page].body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(pages.size) { i ->
                            val active = 1f - abs(progress - i).coerceIn(0f, 1f)
                            Box(
                                Modifier.height(8.dp).width(8.dp + 18.dp * active)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f + 0.75f * active)),
                            )
                        }
                    }
                    AnimatedVisibility(index < pages.lastIndex, enter = fadeIn(), exit = fadeOut()) { TextButton(onClick = onDone) { Text("Skip") } }
                    Button(
                        onClick = {
                            when {
                                asksPermission -> notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                index < pages.lastIndex -> next()
                                else -> onDone()
                            }
                        },
                        modifier = Modifier.animateContentSize(),
                    ) {
                        AnimatedContent(
                            when { asksPermission -> "Allow notifications"; index < pages.lastIndex -> "Next"; else -> "Get started" },
                            transitionSpec = { (fadeIn(tween(220)) + slideInVertically { it / 2 }).togetherWith(fadeOut(tween(150)) + slideOutVertically { -it / 2 }) },
                            label = "cta",
                        ) { label -> Text(label, maxLines = 1) }
                    }
                }
            }
        }
    }
}

/** A page picture: a frosted circle on the brand panel with the icon floating gently. */
@Composable
private fun FloatingIcon(icon: ImageVector, anims: Boolean) {
    val t = if (anims) rememberInfiniteTransition(label = "float").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "float",
    ).value else 0.5f
    Box(Modifier.size(132.dp).graphicsLayer { translationY = (t - 0.5f) * 14.dp.toPx() }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.14f), CircleShape))
        Box(Modifier.size(96.dp).background(Color.White.copy(alpha = 0.18f), CircleShape))
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(48.dp))
    }
}
