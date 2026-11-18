package com.sdm.downloadmanager

import android.content.Intent
import android.net.Uri
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sdm.downloadmanager.feature.player.PlayerActivity
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A real H.264/AAC MP4 opens in SDM's player and actually plays (position moves, no error). */
@RunWith(AndroidJUnit4::class)
class PlayerPlaybackTest {
    @Test fun playsAnMp4() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.cacheDir, "player-test.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("sample.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        val intent = Intent(ctx, PlayerActivity::class.java).setDataAndType(Uri.fromFile(file), "video/mp4").putExtra("title", "sample.mp4")
        ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
            var state = Player.STATE_IDLE
            var position = 0L
            var error: String? = null
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline && !(state == Player.STATE_READY && position > 500) && error == null) {
                Thread.sleep(250)
                scenario.onActivity { a ->
                    state = a.playerForTest?.playbackState ?: Player.STATE_IDLE
                    position = a.playerForTest?.currentPosition ?: 0
                    error = a.errorForTest
                }
            }
            assertNull("player error: $error", error)
            assertTrue("state=$state position=$position", position > 500)
        }
    }
}
