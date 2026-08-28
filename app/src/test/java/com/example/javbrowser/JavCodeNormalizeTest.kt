package com.example.javbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 驗證 AV 資訊（番號）是否能被正規化與正確載入。
 * 番號正規化是 MissAV/JavDB/JavTrailers 等資訊查詢的第一道工序。
 */
class JavCodeNormalizeTest {

    @Test
    fun keepsLeadingZerosForSingleSegmentNumbers() {
        assertEquals("LULU-095", JavDbScraper.normalizeJavCode("lulu-095"))
        assertEquals("WAAA-00632", JavDbScraper.normalizeJavCode("waaa-00632"))
    }

    @Test
    fun mergesMultiSegmentNumbers() {
        assertEquals("REBD-01022", JavDbScraper.normalizeJavCode("REBD-01-022"))
    }

    @Test
    fun normalizesFc2Variants() {
        assertEquals("FC2-PPV-123456", JavDbScraper.normalizeJavCode("FC2 PPV-123456"))
        assertEquals("FC2-PPV-123456", JavDbScraper.normalizeJavCode("FC2PPV-123456"))
        assertEquals("FC2-PPV-123456", JavDbScraper.normalizeJavCode("FC2-PPV-123456"))
        assertEquals("FC2-PPV-123456", JavDbScraper.normalizeJavCode("fc2-ppv-123456"))
    }

    @Test
    fun normalizesUnicodeDashes() {
        assertEquals("SSIS-123", JavDbScraper.normalizeJavCode("SSIS–123"))
        assertEquals("SSIS-123", JavDbScraper.normalizeJavCode("SSIS—123"))
    }

    @Test
    fun extractJavCodeFromTitles() {
        assertEquals("OTIN-024", JavDbScraper.extractJavCode("OTIN-024 Some Title Here"))
        assertNull(JavDbScraper.extractJavCode("no code present"))
    }
}
