package com.sdm.core.common

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePolicyTest {
    @After fun reset() { SourcePolicy.blockVideoPlatforms = false }

    @Test fun offByDefault() {
        assertFalse(SourcePolicy.isBlocked("https://www.youtube.com/watch?v=abc"))
    }

    @Test fun blocksYouTubeHostsWhenOn() {
        SourcePolicy.blockVideoPlatforms = true
        listOf(
            "https://www.youtube.com/watch?v=abc", "https://m.youtube.com/shorts/x", "https://youtu.be/abc",
            "https://music.youtube.com/watch?v=1", "https://rr3---sn-abc.googlevideo.com/videoplayback?x=1",
            "https://www.youtube-nocookie.com/embed/abc", "HTTPS://WWW.YOUTUBE.COM/",
        ).forEach { assertTrue(it, SourcePolicy.isBlocked(it)) }
    }

    @Test fun leavesOtherSitesAlone() {
        SourcePolicy.blockVideoPlatforms = true
        listOf("https://example.com/video.mp4", "https://notyoutube.com/a", "https://youtube.com.evil.example/x", "ftp://files.example.org/a.zip", "")
            .forEach { assertFalse(it, SourcePolicy.isBlocked(it)) }
    }
}
