package com.example.javbrowser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object RecordingEventParser {
    private const val EMPTY_MESSAGE_ZH = "此筆通知沒有附帶其他互動內容"
    private const val EMPTY_MESSAGE_EN = "No interaction details were included"

    fun parse(context: Context, uri: Uri, durationMs: Long? = null): RecordingEventParseResult {
        return try {
            val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                it.readText()
            } ?: return RecordingEventParseResult(damaged = true)
            val root = JSONObject(raw)
            val sourceEvents = root.optJSONArray("events") ?: JSONArray()
            val seenIds = hashSetOf<String>()
            val parsed = mutableListOf<RecordingTimelineEvent>()
            for (index in 0 until sourceEvents.length()) {
                val item = sourceEvents.optJSONObject(index) ?: continue
                runCatching {
                    val offset = item.optDouble("offsetSeconds", Double.NaN)
                    if (!offset.isFinite() || offset < 0.0) return@runCatching
                    if (durationMs != null && durationMs > 0L && offset > durationMs / 1000.0 + 2.0) {
                        return@runCatching
                    }
                    val id = item.optString("id").trim()
                    if (id.isNotEmpty() && !seenIds.add(id)) return@runCatching
                    parsed += RecordingTimelineEvent(
                        id = id,
                        occurredAt = item.optString("occurredAt"),
                        offsetSeconds = offset,
                        username = item.optString("username"),
                        tokens = item.optInt("tokens", 0).coerceAtLeast(0),
                        message = item.optString("message").trim(),
                        rawText = item.optString("rawText")
                    )
                }
            }
            RecordingEventParseResult(
                document = RecordingEventDocument(
                    version = root.optInt("version", 1),
                    videoFileName = root.optString("videoFileName"),
                    events = parsed.sortedBy { it.offsetSeconds }
                )
            )
        } catch (_: Exception) {
            RecordingEventParseResult(damaged = true)
        }
    }

    fun playerPayload(context: Context, uri: Uri?): String {
        if (uri == null) return JSONObject().put("events", JSONArray()).put("damaged", false).toString()
        val result = parse(context, uri)
        val events = JSONArray()
        result.document.events.forEach { event ->
            events.put(
                JSONObject()
                    .put("id", event.id)
                    .put("occurredAt", event.occurredAt)
                    .put("offsetSeconds", event.offsetSeconds)
                    .put("username", event.username)
                    .put("tokens", event.tokens)
                    .put(
                        "message",
                        event.message.ifBlank {
                            LanguageManager.text(context, EMPTY_MESSAGE_ZH, EMPTY_MESSAGE_EN)
                        }
                    )
            )
        }
        return JSONObject()
            .put("events", events)
            .put("damaged", result.damaged)
            .toString()
    }
}
