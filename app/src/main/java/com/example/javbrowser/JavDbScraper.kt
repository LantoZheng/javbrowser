package com.example.javbrowser

import android.content.Context
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class JavDbScraper(private val context: Context) {

    private val apiToken: String
        get() = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getString("scrapedo_token", "") ?: ""

    companion object {
        // Matches codes like OTIN-024, IPX-123, FC2-PPV-123456, REBD-01-022
        private val FC2_CODE_REGEX = Regex(
            "(?<![A-Z0-9])FC2(?:[-_\\s]?PPV)?[-_\\s]?(\\d{5,10})(?!\\d)",
            RegexOption.IGNORE_CASE
        )
        private val JAV_CODE_REGEX = Regex("^([A-Z0-9]{2,10}-\\d{1,5}(?:-\\d{2,5})?)", RegexOption.IGNORE_CASE)

        /**
         * 正規化番號：統一大小寫與分隔格式。
         * 單段數字保留前導零，避免 LULU-095 被錯誤改成 LULU-95。
         * 多段數字則合併為單段，但保留原本數字內容。
         * REBD-01-022  → REBD-01022
         * WAAA-00632   → WAAA-00632
         * XVSR-851     → XVSR-851
         * FC2 PPV-123456 / FC2PPV-123456 / FC2-PPV-123456 → FC2-PPV-123456
         */
        fun normalizeJavCode(code: String): String {
            val upper = code.trim()
                .replace('–', '-')
                .replace('—', '-')
                .replace('＿', '-')
                .uppercase()

            extractFc2Code(upper)?.let {
                return it
            }

            FC2_CODE_REGEX.find(upper)?.let { match ->
                return "FC2-PPV-${match.groupValues[1]}"
            }

            val m = Regex("^([A-Z0-9]+)-(\\d+(?:-\\d+)*)$").find(upper) ?: return upper
            val prefix = m.groupValues[1]
            val numberPart = m.groupValues[2]
            val normalizedNumber = if (numberPart.contains("-")) numberPart.replace("-", "") else numberPart
            return "$prefix-$normalizedNumber"
        }

        fun extractJavCode(title: String): String? {
            val cleaned = title.trim()
                .replace('–', '-')
                .replace('—', '-')
                .replace('＿', '-')

            extractFc2Code(cleaned)?.let { return it }

            val raw = JAV_CODE_REGEX.find(cleaned)?.groupValues?.get(1)?.uppercase()
                ?: return null
            return normalizeJavCode(raw)
        }

        /**
         * 接受不同網站常見的 FC2 寫法，並優先採用數字最完整的候選：
         * FC2-PPV-3175924 / FC2PPV-4934472 / FC2-4775113 / FC2 PPV 1234567。
         */
        fun extractFc2Code(vararg values: String): String? = values
            .asSequence()
            .flatMap { value -> FC2_CODE_REGEX.findAll(value).asSequence() }
            .map { match -> match.groupValues[1] }
            .maxByOrNull { digits -> digits.length }
            ?.let { digits -> "FC2-PPV-$digits" }

        fun isFc2Code(value: String?): Boolean =
            !value.isNullOrBlank() && extractFc2Code(value) != null
    }

    /**
     * Two-step scrape: search JavDB → parse detail page.
     * Delegates to [JavDbWebViewScraper] when "webview" method is selected in settings.
     * Runs on a background thread (Scrape.do path) or main thread (WebView path).
     * Callers must switch to the main thread themselves (runOnUiThread) for UI updates.
     */
    fun enrichFavorite(javCode: String, callback: (JavVideoDetail?) -> Unit) {
        val method = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getString("enrich_method", "scrapedo")
        if (method == "webview") {
            JavDbWebViewScraper(context).enrichFavorite(javCode, callback)
            return
        }

        val token = apiToken
        if (token.isEmpty()) {
            callback(null)
            return
        }

        thread {
            try {
                // ── Step 1: search page ──────────────────────────────────────────────
                val searchUrl = "https://javdb.com/search?q=${URLEncoder.encode(javCode, "UTF-8")}&locale=zh"
                val apiUrl1 = "https://api.scrape.do/?token=$token" +
                        "&url=${URLEncoder.encode(searchUrl, "UTF-8")}&render=true"

                val html1 = fetchUrl(apiUrl1) ?: run { callback(null); return@thread }

                val doc1 = Jsoup.parse(html1)

                // Actual structure: .item > a.box, code is in .video-title strong
                var detailUrl = ""
                for (item in doc1.select(".item")) {
                    val codeText = item.select(".video-title strong").text().trim()
                    if (codeText.equals(javCode, ignoreCase = true)) {
                        val href = item.select("a.box").attr("href")
                        if (href.isNotEmpty()) {
                            detailUrl = "https://javdb.com$href"
                            break
                        }
                    }
                }

                if (detailUrl.isEmpty()) {
                    android.util.Log.w("JavDbScraper", "No result found for $javCode")
                    callback(null)
                    return@thread
                }

                // ── Step 2: detail page (super=true for residential IP) ───────────────
                val detailUrlZh = if (detailUrl.contains("?")) "$detailUrl&locale=zh" else "$detailUrl?locale=zh"
                val apiUrl2 = "https://api.scrape.do/?token=$token" +
                        "&url=${URLEncoder.encode(detailUrlZh, "UTF-8")}&render=true&super=true"

                val html2 = fetchUrl(apiUrl2) ?: run { callback(null); return@thread }

                val doc2 = Jsoup.parse(html2)

                // Supports both English and Chinese label variants
                // Real HTML uses English: "Released Date:", "Actor(s):", "Tags:", etc.
                fun extractMeta(vararg labels: String): String {
                    for (label in labels) {
                        val el = doc2.select("strong:contains($label)").first()
                        if (el != null) {
                            return el.nextElementSibling()?.text()?.trim() ?: ""
                        }
                    }
                    return ""
                }

                fun extractMetaList(vararg labels: String): List<String> {
                    for (label in labels) {
                        val el = doc2.select("strong:contains($label)").first()
                        if (el != null) {
                            val result = el.nextElementSibling()
                                ?.select("a")
                                ?.map { it.text().trim() }
                                ?.filter { it.isNotEmpty() }
                            if (!result.isNullOrEmpty()) return result
                        }
                    }
                    return emptyList()
                }

                // Actors: include gender symbol (♀/♂) from the <strong class="symbol"> sibling
                fun extractActors(vararg labels: String): List<String> {
                    for (label in labels) {
                        val el = doc2.select("strong:contains($label)").first()
                        val container = el?.nextElementSibling() ?: continue
                        val result = mutableListOf<String>()
                        for (a in container.select("a")) {
                            val name = a.text().trim()
                            if (name.isEmpty()) continue
                            val symbol = a.nextElementSibling()
                                ?.takeIf { it.hasClass("symbol") }
                                ?.text()?.trim() ?: ""
                            result.add(name + symbol)
                        }
                        if (result.isNotEmpty()) return result
                    }
                    return emptyList()
                }

                // Rating: raw text is "4.25, by 100 users" — extract just the number+score part
                // locale=zh → labels are Chinese; keep English as fallback
                val ratingRaw = extractMeta("評分:", "Rating:")
                val rating = ratingRaw.trimStart().removePrefix("&nbsp;").trim()

                val detail = JavVideoDetail(
                    code = javCode,
                    title = doc2.select(".title strong, .video-detail-header h2").first()
                        ?.text()?.trim() ?: "",
                    date = extractMeta("日期:", "發行日期:", "Released Date:"),
                    duration = extractMeta("時長:", "片長:", "Duration:"),
                    maker = extractMeta("片商:", "發行商:", "Maker:"),
                    series = extractMeta("系列:", "Series:"),
                    rating = rating,
                    genres = extractMetaList("類別:", "標籤:", "Tags:"),
                    actors = extractActors("演員:", "女優:", "Actor(s):"),
                    detailUrl = detailUrl
                )

                android.util.Log.d(
                    "JavDbScraper",
                    "Enriched $javCode → date=${detail.date} maker=${detail.maker} actors=${detail.actors}"
                )
                callback(detail)

            } catch (e: Exception) {
                android.util.Log.e("JavDbScraper", "Error scraping $javCode: ${e.message}")
                callback(null)
            }
        }
    }

    private fun fetchUrl(url: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            android.util.Log.e("JavDbScraper", "fetchUrl error: ${e.message}")
            null
        }
    }
}
