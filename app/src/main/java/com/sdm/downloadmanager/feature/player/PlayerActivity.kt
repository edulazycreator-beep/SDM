package com.sdm.downloadmanager.feature.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import com.sdm.core.designsystem.i18n.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.sdm.core.designsystem.i18n.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.sdm.core.common.FileNames
import com.sdm.core.database.SdmDatabase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Plays downloaded video and audio inside SDM (Media3). Subtitles saved next to a video
 * (`name.<lang>.srt`) are offered automatically. Supports picture-in-picture.
 */
@AndroidEntryPoint
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class) // PlayerView subtitle button
class PlayerActivity : ComponentActivity() {
    @Inject lateinit var db: SdmDatabase
    private var player: ExoPlayer? = null
    private var inPip by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: uri.lastPathSegment ?: "Video"
        val p = ExoPlayer.Builder(this).build().also { player = it }
        lifecycleScope.launch {
            val subs = sidecarSubtitles(title)
            val item = MediaItem.Builder().setUri(uri).setSubtitleConfigurations(subs.map { (lang, u) ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(u)).setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage(lang).setLabel(lang).build()
            }).build()
            p.setMediaItem(item, savedInstanceState?.getLong(KEY_POSITION) ?: 0L)
            p.prepare()
            p.playWhenReady = true
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory = { ctx ->
                        PlayerView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            this.player = p
                            setShowSubtitleButton(true)
                            keepScreenOn = true
                        }
                    }, update = { it.useController = !inPip }, modifier = Modifier.fillMaxSize())
                    if (!inPip) Row(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White) }
                        Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (Build.VERSION.SDK_INT >= 26) IconButton(onClick = { enterPip() }) { Icon(Icons.Outlined.PictureInPictureAlt, "Picture in picture", tint = Color.White) }
                    }
                }
            }
        }
    }

    private suspend fun sidecarSubtitles(title: String): List<Pair<String, String>> = runCatching {
        val base = FileNames.baseName(title)
        db.files().search("$base.%.srt").filter { it.name.startsWith("$base.") && it.name.endsWith(".srt") }
            .map { it.name.removePrefix("$base.").removeSuffix(".srt") to it.uri }
    }.getOrDefault(emptyList())

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) return
        val size = player?.videoSize
        val ratio = if (size != null && size.width > 0 && size.height > 0) Rational(size.width, size.height).coerceRatio() else Rational(16, 9)
        runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build()) }
    }

    /** Android only accepts aspect ratios between 1:2.39 and 2.39:1. */
    private fun Rational.coerceRatio(): Rational = when {
        toFloat() > 2.39f -> Rational(239, 100)
        toFloat() < 1 / 2.39f -> Rational(100, 239)
        else -> this
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true && (player?.videoSize?.width ?: 0) > 0) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        if (!inPip) player?.pause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let { outState.putLong(KEY_POSITION, it.currentPosition) }
    }

    override fun onDestroy() {
        player?.release(); player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val KEY_POSITION = "position"

        fun start(context: Context, uri: Uri, title: String?, mime: String?) {
            context.startActivity(Intent(context, PlayerActivity::class.java).setDataAndType(uri, mime).putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        fun canPlay(mime: String?) = mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))
    }
}
