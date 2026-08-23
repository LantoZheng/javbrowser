package com.example.javbrowser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object PcRecordingCache {
    private const val FILE_NAME = "pc_recording_library_cache.json"
    private const val CACHE_VERSION = 1

    data class Snapshot(
        val recordings: List<PcRecordingItem>,
        val savedAt: Long
    )

    fun load(context: Context, treeUri: Uri): Snapshot? = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return null
        val root = JSONObject(file.readText(Charsets.UTF_8))
        if (root.optInt("version", 0) != CACHE_VERSION) return null
        if (root.optString("treeUri") != treeUri.toString()) return null
        val items = root.optJSONArray("recordings") ?: JSONArray()
        val recordings = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val videoUri = item.optString("videoUri").takeIf { it.isNotBlank() } ?: continue
                val fileName = item.optString("fileName").takeIf { it.isNotBlank() } ?: continue
                add(
                    PcRecordingItem(
                        videoUri = Uri.parse(videoUri),
                        fileName = fileName,
                        channelName = item.optString("channelName", "PC Recording"),
                        recordedAt = item.optLong("recordedAt", 0L),
                        sizeBytes = item.optLong("sizeBytes", 0L),
                        durationMs = if (item.isNull("durationMs")) null else item.optLong("durationMs"),
                        contactSheetUri = item.optNullableUri("contactSheetUri"),
                        eventsUri = item.optNullableUri("eventsUri"),
                        eventCount = item.optInt("eventCount", 0),
                        eventsDamaged = item.optBoolean("eventsDamaged", false),
                        lastModified = item.optLong("lastModified", 0L)
                    )
                )
            }
        }
        Snapshot(recordings, root.optLong("savedAt", 0L))
    }.getOrNull()

    fun save(context: Context, treeUri: Uri, recordings: List<PcRecordingItem>) {
        val items = JSONArray()
        recordings.forEach { recording ->
            items.put(
                JSONObject().apply {
                    put("videoUri", recording.videoUri.toString())
                    put("fileName", recording.fileName)
                    put("channelName", recording.channelName)
                    put("recordedAt", recording.recordedAt)
                    put("sizeBytes", recording.sizeBytes)
                    put("durationMs", recording.durationMs ?: JSONObject.NULL)
                    put("contactSheetUri", recording.contactSheetUri?.toString() ?: JSONObject.NULL)
                    put("eventsUri", recording.eventsUri?.toString() ?: JSONObject.NULL)
                    put("eventCount", recording.eventCount)
                    put("eventsDamaged", recording.eventsDamaged)
                    put("lastModified", recording.lastModified)
                }
            )
        }
        val root = JSONObject().apply {
            put("version", CACHE_VERSION)
            put("treeUri", treeUri.toString())
            put("savedAt", System.currentTimeMillis())
            put("recordings", items)
        }
        val target = File(context.filesDir, FILE_NAME)
        val temporary = File(context.filesDir, "$FILE_NAME.tmp")
        temporary.writeText(root.toString(), Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            temporary.delete()
            error("Unable to replace PC recording cache")
        }
        if (!temporary.renameTo(target)) {
            target.writeText(root.toString(), Charsets.UTF_8)
            temporary.delete()
        }
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
        File(context.filesDir, "$FILE_NAME.tmp").delete()
    }

    private fun JSONObject.optNullableUri(key: String): Uri? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }?.let(Uri::parse)
    }
}
