package com.example.javbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 驗證各站點的視頻源 URL 是否能從頁面 HTML 正確解析。
 * 測試資料依照各站實際頁面結構精簡而成。
 */
class VideoExtractorTest {

    // ---------- Jable ----------

    @Test
    fun jable_extractsHlsUrlFromInlineScript() {
        val html = """
            <html><head><script>
            var hlsUrl = 'https://hot-sp2-cdnjable.contentdef.com/hls/abc123/index.m3u8';
            </script></head><body></body></html>
        """.trimIndent()
        assertEquals(
            "https://hot-sp2-cdnjable.contentdef.com/hls/abc123/index.m3u8",
            VideoExtractor.extractJable(html)
        )
    }

    @Test
    fun jable_returnsNullWhenNoHlsUrl() {
        assertNull(VideoExtractor.extractJable("<html><body>no video here</body></html>"))
    }

    @Test
    fun jable_extractsVttUrlForTimelinePreview() {
        val html = """var vttUrl = "https://cdn.jable.tv/thumbvtt/abc/sprite.vtt";"""
        assertEquals(
            "https://cdn.jable.tv/thumbvtt/abc/sprite.vtt",
            VideoExtractor.extractJableVttUrl(html)
        )
    }

    @Test
    fun jable_vttUrlRejectsNonThumbvttUrl() {
        val html = """var vttUrl = "https://cdn.jable.tv/subtitles/abc.vtt";"""
        assertNull(VideoExtractor.extractJableVttUrl(html))
    }

    // ---------- MissAV ----------

    @Test
    fun missav_fallbackExtractsUuidFromSeekThumbnails() {
        val html = """
            <script>
            thumbnail: {
            urls: ["https:\/\/nineyu.com\/550e8400-e29b-41d4-a716-446655440000\/seek\/_0.jpg"]
            pic_num: 100
            </script>
        """.trimIndent()
        assertEquals(
            "https://surrit.com/550e8400-e29b-41d4-a716-446655440000/playlist.m3u8",
            VideoExtractor.extractMissAV(html)
        )
    }

    @Test
    fun missav_decodesPackerForHighestQuality() {
        // 模擬 dean.edwards packer：dict 索引 0=https 1=surrit 2=com 3=playlist 4=m3u8 5=source1280 6=uuid
        // payload 內部單引號已跳脫（與真實頁面一致）
        val payload = "var a=\\'0://1.2/6/3.4\\';"
        val html = "eval(function(p,a,c,k,e,d){/*...*/}('$payload',36,7," +
            "'https|surrit|com|playlist|m3u8|source1280|abc-uuid-123'.split('|'),0,{}))"
        assertEquals(
            "https://surrit.com/abc-uuid-123/playlist.m3u8",
            VideoExtractor.extractMissAV(html)
        )
    }

    @Test
    fun missav_returnsNullForUnrelatedHtml() {
        assertNull(VideoExtractor.extractMissAV("<html><body>404 not found</body></html>"))
    }

    @Test
    fun missav_extractsThumbnailConfig() {
        val html = """
            <script>
            thumbnail: {
            enabled: true,
            urls: ["https:\/\/nineyu.com\/abc-def\/seek\/_0.jpg"],
            width: 160,
            height: 90,
            col: 10,
            row: 10,
            pic_num: 100
            },
            keyboard: {}
            </script>
        """.trimIndent()
        val config = VideoExtractor.extractMissAvThumbnailConfig(html)
        assertEquals(100, config?.picNum)
        assertEquals(160, config?.width)
        assertEquals(90, config?.height)
        assertEquals(10, config?.columns)
        assertEquals(10, config?.rows)
        assertTrue(config?.urlTemplate?.contains("_{index}.jpg") == true)
    }

    // ---------- RouVideo ----------

    @Test
    fun rouVideo_fallbackExtractsVideoTagM3u8() {
        val html = """
            <video id="player" src="https://cdn.rouva3.xyz/hls/xyz/index.m3u8?token=a&amp;b=1"></video>
        """.trimIndent()
        assertEquals(
            "https://cdn.rouva3.xyz/hls/xyz/index.m3u8?token=a&b=1",
            VideoExtractor.extractRouVideo(html)
        )
    }

    @Test
    fun rouVideo_returnsNullWhenNoSource() {
        assertNull(VideoExtractor.extractRouVideo("<html><body>empty</body></html>"))
    }

    // ---------- AvJoy ----------

    @Test
    fun avJoy_picksHighestResolutionSource() {
        val html = """
            <video>
              <source src="https://cdn.avjoy.me/v/abc-360.mp4" res="360">
              <source src="https://cdn.avjoy.me/v/abc-720.mp4" res="720">
              <source src="https://cdn.avjoy.me/v/abc-480.mp4" res="480">
            </video>
        """.trimIndent()
        assertEquals("https://cdn.avjoy.me/v/abc-720.mp4", VideoExtractor.extractAvJoy(html))
    }

    @Test
    fun avJoy_fallbackToVideoTagMp4() {
        val html = """<video src="https://cdn.avjoy.me/v/abc.mp4" controls></video>"""
        assertEquals("https://cdn.avjoy.me/v/abc.mp4", VideoExtractor.extractAvJoy(html))
    }

    // ---------- PigAV ----------

    @Test
    fun pigav_extractsEscapedM3u8() {
        val html = """{"file":"https:\/\/cdn.pigav.ws\/hls\/def\/playlist.m3u8?sig=xyz"}"""
        assertEquals(
            "https://cdn.pigav.ws/hls/def/playlist.m3u8?sig=xyz",
            VideoExtractor.extractPigAV(html)
        )
    }

    // ---------- Generic (JavGuru / SupJav / Netflav) ----------

    @Test
    fun generic_prefersM3u8OverMp4() {
        val html = """
            <script>var mp4 = "https://cdn.supjav.com/v/1.mp4";</script>
            <script>var hls = "https:\/\/cdn.supjav.com\/v\/1\/master.m3u8";</script>
        """.trimIndent()
        assertEquals("https://cdn.supjav.com/v/1/master.m3u8", VideoExtractor.extractGeneric(html))
    }

    @Test
    fun generic_decodesUnicodeEscapedAmpersand() {
        val html = """"https://cdn.jav.guru/hls/a/playlist.m3u8?token=x&amp;y=2""""
        val url = VideoExtractor.extractGeneric(html)
        assertEquals("https://cdn.jav.guru/hls/a/playlist.m3u8?token=x&y=2", url)
    }

    @Test
    fun generic_returnsNullWhenNothingPlayable() {
        assertNull(VideoExtractor.extractGeneric("<html><body>no streams</body></html>"))
    }
}
