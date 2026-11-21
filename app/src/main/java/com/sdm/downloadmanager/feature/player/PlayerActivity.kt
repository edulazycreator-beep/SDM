package com.sdm.downloadmanager.feature.player

import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
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
    private var error by mutableStateOf<String?>(null)
    /** For tests: the player and the error shown, if any. */
    internal val playerForTest: ExoPlayer? get() = player
    internal val errorForTest: String? get() = error
    private var withSubtitles = true
    private lateinit var source: Uri
    private var mime: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: uri.lastPathSegment ?: "Video"
        val p = ExoPlayer.Builder(this).build().also { player = it }
        source = uri
        mime = intent.type
        p.addListener(object : Player.Listener {
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                // A subtitle file that can't be read must not stop the video: retry without it once.
                if (withSubtitles) { withSubtitles = false; load(p, title, p.currentPosition); return }
                error = describe(e)
            }
        })
        load(p, title, savedInstanceState?.getLong(KEY_POSITION) ?: 0L)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent {
            MaterialTheme {
                var resize by remember { mutableIntStateOf(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT) }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory = { ctx ->
                        PlayerView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            this.player = p
                            useController = false
                            keepScreenOn = true
                        }
                    }, update = { it.resizeMode = resize }, modifier = Modifier.fillMaxSize())
                    PlayerControls(
                        player = p, title = title, inPip = inPip, resizeMode = resize, onResizeMode = { resize = it },
                        onBack = { finish() }, onPip = if (Build.VERSION.SDK_INT >= 26) ({ enterPip() }) else null,
                    )
                    error?.let { msg ->
                        PlaybackError(
                            message = msg,
                            onRetry = { withSubtitles = true; load(p, title, p.currentPosition) },
                            onOpenWith = { startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(source, mime ?: "video/*").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), com.sdm.core.common.I18n.tr("Open with"))) },
                            onBack = { finish() },
                        )
                    }
                }
            }
        }
    }

    private fun load(p: ExoPlayer, title: String, position: Long) {
        error = null
        lifecycleScope.launch {
            val subs = if (withSubtitles) sidecarSubtitles(title) else emptyList()
            val item = MediaItem.Builder().setUri(source).setSubtitleConfigurations(subs.map { (lang, u) ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(u)).setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage(lang).setLabel(lang).build()
            }).build()
            p.setMediaItem(item, position)
            p.prepare()
            p.playWhenReady = true
        }
    }

    private fun describe(e: androidx.media3.common.PlaybackException): String = when (e.errorCode) {
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The file was not found. It may have been moved or deleted."
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "SDM has no permission to read this file."
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "This file's format can't be read by the built-in player."
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "This phone can't decode this video's format."
        else -> "Playback failed (${e.errorCodeName})."
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
