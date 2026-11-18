package com.sdm.downloadmanager.feature.player

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.sdm.core.designsystem.i18n.Icon
import com.sdm.core.designsystem.i18n.Text
import com.sdm.core.designsystem.theme.Poppins
import kotlinx.coroutines.delay

private val White = Color.White
private val Muted = Color.White.copy(alpha = 0.75f)
private fun style(size: Int, weight: FontWeight = FontWeight.Medium) = TextStyle(fontFamily = Poppins, fontSize = size.sp, fontWeight = weight, color = White)

fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600; val m = total / 60 % 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private data class SubtitleChoice(val label: String, val group: androidx.media3.common.Tracks.Group?, val index: Int)

/**
 * SDM's own video controls over the Media3 surface: auto-hiding bars, big play button, ±10 s
 * (buttons or double-tap on either side), a seek slider, speed, subtitles, fit/fill and
 * picture-in-picture.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerControls(
    player: Player,
    title: String,
    inPip: Boolean,
    resizeMode: Int,
    onResizeMode: (Int) -> Unit,
    onBack: () -> Unit,
    onPip: (() -> Unit)?,
) {
    var playing by remember { mutableStateOf(player.isPlaying) }
    var buffering by remember { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    var position by remember { mutableLongStateOf(player.currentPosition) }
    var duration by remember { mutableLongStateOf(player.duration.coerceAtLeast(0)) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var visible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    var speedMenu by remember { mutableStateOf(false) }
    var subsMenu by remember { mutableStateOf(false) }
    var seekHint by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
    var tracks by remember { mutableStateOf(player.currentTracks) }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING; duration = player.duration.coerceAtLeast(0) }
            override fun onTracksChanged(t: androidx.media3.common.Tracks) { tracks = t }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            if (duration <= 0) duration = player.duration.coerceAtLeast(0)
            delay(250)
        }
    }
    // Hide the controls 3 s after the last touch while the video plays.
    LaunchedEffect(visible, playing, interaction) {
        if (visible && playing && dragging == null && !speedMenu && !subsMenu) { delay(3000); visible = false }
    }
    LaunchedEffect(seekHint) { if (seekHint != null) { delay(700); seekHint = null } }

    fun touch() { interaction++ }
    fun seekBy(ms: Long) {
        player.seekTo((player.currentPosition + ms).coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE))
        position = player.currentPosition
        touch()
    }

    val subtitleChoices = remember(tracks) {
        val list = mutableListOf(SubtitleChoice("Off", null, -1))
        tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEach { g ->
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                list += SubtitleChoice(f.label ?: f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it } } ?: "Subtitles", g, i)
            }
        }
        list
    }
    val activeSubtitle = subtitleChoices.firstOrNull { c -> c.group != null && c.group.isTrackSelected(c.index) }

    Box(
        Modifier.fillMaxSize().pointerInput(duration) {
            detectTapGestures(
                onTap = { visible = !visible; touch() },
                onDoubleTap = { o ->
                    val forward = o.x > size.width / 2
                    seekBy(if (forward) 10_000 else -10_000)
                    seekHint = forward to ((seekHint?.takeIf { it.first == forward }?.second ?: 0) + 10)
                },
            )
        },
    ) {
        if (inPip) return@Box

        // Double-tap feedback on the side that was tapped.
        seekHint?.let { (forward, secs) ->
            Box(
                Modifier.align(if (forward) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 48.dp)
                    .clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 18.dp, vertical = 10.dp),
            ) { Text(if (forward) "+$secs s" else "−$secs s", style = style(16, FontWeight.SemiBold)) }
        }

        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Top: back, title, subtitles, picture-in-picture.
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .safeDrawingPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = White) }
                    Text(title, style = style(15, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (subtitleChoices.size > 1) Box {
                        IconButton(onClick = { subsMenu = true; touch() }) { Icon(Icons.Outlined.Subtitles, "Subtitles", tint = if (activeSubtitle != null) White else Muted) }
                        DropdownMenu(subsMenu, { subsMenu = false }) {
                            subtitleChoices.forEach { c ->
                                DropdownMenuItem(text = { Text(c.label + if (c == activeSubtitle || (c.group == null && activeSubtitle == null)) "  ✓" else "") }, onClick = {
                                    subsMenu = false
                                    val params = player.trackSelectionParameters.buildUpon()
                                    if (c.group == null) params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                    else params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(c.group.mediaTrackGroup, c.index))
                                    player.trackSelectionParameters = params.build()
                                })
                            }
                        }
                    }
                    if (onPip != null) IconButton(onClick = onPip) { Icon(Icons.Outlined.PictureInPictureAlt, "Picture in picture", tint = White) }
                }

                // Center: −10 s, play/pause, +10 s.
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(Icons.Outlined.Replay10, "Back 10 seconds", 56) { seekBy(-10_000) }
                    Box(contentAlignment = Alignment.Center) {
                        if (buffering) CircularProgressIndicator(Modifier.size(84.dp), color = White, strokeWidth = 3.dp)
                        RoundButton(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "Pause" else "Play", 76) {
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                            if (playing) player.pause() else player.play()
                            touch()
                        }
                    }
                    RoundButton(Icons.Outlined.Forward10, "Forward 10 seconds", 56) { seekBy(10_000) }
                }

                // Bottom: time, slider, speed and fit/fill.
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                        .safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    val shown = dragging?.let { (it * duration).toLong() } ?: position
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(shown), style = style(13, FontWeight.SemiBold))
                        Text(" / " + formatTime(duration), style = style(13).copy(color = Muted))
                        Spacer(Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { speedMenu = true; touch() }) { Text(if (speed == 1f) "1×" else "${speed}×", style = style(13, FontWeight.SemiBold)) }
                            DropdownMenu(speedMenu, { speedMenu = false }) {
                                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { v ->
                                    DropdownMenuItem(text = { Text("${v}×" + if (v == speed) "  ✓" else "") }, onClick = { speed = v; player.setPlaybackSpeed(v); speedMenu = false })
                                }
                            }
                        }
                        IconButton(onClick = {
                            onResizeMode(if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) AspectRatioFrameLayout.RESIZE_MODE_FIT else AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
                            touch()
                        }) { Icon(Icons.Outlined.AspectRatio, if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) "Fit to screen" else "Fill screen", tint = White) }
                    }
                    Slider(
                        value = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = { dragging = it; touch() },
                        onValueChangeFinished = { dragging?.let { player.seekTo((it * duration).toLong()) }; dragging = null },
                        enabled = duration > 0,
                        colors = SliderDefaults.colors(thumbColor = White, activeTrackColor = White, inactiveTrackColor = White.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, sizeDp: Int, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(sizeDp.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.Black.copy(alpha = 0.35f), contentColor = White),
    ) { Icon(icon, label, modifier = Modifier.size((sizeDp * 0.5f).dp)) }
}
