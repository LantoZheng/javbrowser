package com.example.javbrowser

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class VideoDownloadRecord(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val status: String,
    val progress: Int,
    val speedBytesPerSecond: Long,
    val message: String,
    val fileName: String?,
    val fileUri: String?,
    val fileSizeBytes: Long,
    val javCode: String?,
    val mimeType: String?,
    val storageLocation: String,
    val createdAt: Long,
    val completedAt: Long?,
    val referer: String,
    val cookieSourceUrl: String
)

object DownloadRepository {
    const val ACTION_DOWNLOADS_CHANGED = "com.example.javbrowser.DOWNLOADS_CHANGED"
    const val STATUS_PENDING = "pending"
    const val STATUS_DOWNLOADING = "downloading"
    const val STATUS_COMPLETED = "completed"
    const val STATUS_FAILED = "failed"
    const val STATUS_CANCELED = "canceled"
    const val STATUS_MISSING = "missing"

    private const val PREFS_NAME = "video_downloads"
    private const val KEY_RECORDS = "records"
    private const val KEY_STORAGE_TREE_URI = "storage_tree_uri"
    private const val KEY_STORAGE_NAME = "storage_name"

    @Synchronized
    fun create(
        context: Context,
        title: String,
        sourceUrl: String,
        referer: String = "",
        cookieSourceUrl: String = ""
    ): VideoDownloadRecord {
        val record = VideoDownloadRecord(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { "video" },
            sourceUrl = sourceUrl,
            status = STATUS_PENDING,
            progress = 0,
            speedBytesPerSecond = 0L,
            message = "等待下載",
            fileName = null,
            fileUri = null,
            fileSizeBytes = 0L,
            javCode = LocalVideoRepository.extractJavCode(title),
            mimeType = null,
            storageLocation = storageDisplayName(context),
            createdAt = System.currentTimeMillis(),
            completedAt = null,
            referer = referer,
            cookieSourceUrl = cookieSourceUrl
        )
        saveRecords(context, (list(context) + record).takeLast(100))
        notifyChanged(context)
        return record
    }

    @Synchronized
    fun update(context: Context, id: String, transform: (VideoDownloadRecord) -> VideoDownloadRecord) {
        val records = list(context).toMutableList()
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return
        records[index] = transform(records[index])
        saveRecords(context, records)
        notifyChanged(context)
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        saveRecords(context, list(context).filterNot { it.id == id })
        notifyChanged(context)
    }

    @Synchronized
    fun clearFinished(context: Context) {
        saveRecords(
            context,
            list(context).filter { it.status == STATUS_PENDING || it.status == STATUS_DOWNLOADING }
        )
        notifyChanged(context)
    }

    @Synchronized
    fun list(context: Context): List<VideoDownloadRecord> {
        val raw = prefs(context).getString(KEY_RECORDS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) add(fromJson(array.getJSONObject(index)))
            }
        }.getOrDefault(emptyList()).sortedByDescending { it.createdAt }
    }

    fun storageTreeUri(context: Context): Uri? = prefs(context)
        .getString(KEY_STORAGE_TREE_URI, null)?.let(Uri::parse)

    fun storageDisplayName(context: Context): String = prefs(context)
        .getString(KEY_STORAGE_NAME, null) ?: "系統下載/JAV Browser"

    fun isManagedRecord(record: VideoDownloadRecord): Boolean =
        record.sourceUrl.isNotBlank() || record.storageLocation != "既有下載檔案"

    fun setStorageTree(context: Context, uri: Uri?, displayName: String?) {
        prefs(context).edit().apply {
            if (uri == null) {
                remove(KEY_STORAGE_TREE_URI)
                remove(KEY_STORAGE_NAME)
            } else {
                putString(KEY_STORAGE_TREE_URI, uri.toString())
                putString(KEY_STORAGE_NAME, displayName ?: "自訂資料夾")
            }
        }.apply()
        notifyChanged(context)
    }

    @Synchronized
    fun syncLocalFiles(
        context: Context,
        files: List<LocalVideoFile>,
        markMissing: Boolean = false,
        pruneUnmanaged: Boolean = false
    ) {
        val records = list(context).toMutableList()
        if (pruneUnmanaged) records.removeAll { !isManagedRecord(it) }
        val availableUris = files.map { it.uri.toString() }.toSet()
        files.forEach { file ->
            val uri = file.uri.toString()
            val uriIndex = records.indexOfFirst { it.fileUri == uri }
            val index = if (uriIndex >= 0) {
                uriIndex
            } else {
                records.indexOfFirst { record ->
                    record.status == STATUS_MISSING && record.fileName == file.fileName
                }
            }
            if (index >= 0) {
                records[index] = records[index].copy(
                    status = STATUS_COMPLETED,
                    progress = 100,
                    message = if (records[index].sourceUrl.isBlank()) "本地影片" else "下載完成",
                    fileName = file.fileName,
                    fileUri = uri,
                    fileSizeBytes = file.sizeBytes,
                    javCode = file.javCode ?: records[index].javCode,
                    mimeType = file.mimeType,
                    storageLocation = file.storageLocation
                )
            } else {
                records.add(
                    VideoDownloadRecord(
                        id = "local-${uri.hashCode()}-${file.fileName.hashCode()}",
                        title = file.fileName.substringBeforeLast('.'),
                        sourceUrl = "",
                        status = STATUS_COMPLETED,
                        progress = 100,
                        speedBytesPerSecond = 0L,
                        message = "本地影片",
                        fileName = file.fileName,
                        fileUri = uri,
                        fileSizeBytes = file.sizeBytes,
                        javCode = file.javCode,
                        mimeType = file.mimeType,
                        storageLocation = file.storageLocation,
                        createdAt = file.modifiedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        completedAt = file.modifiedAt.takeIf { it > 0L },
                        referer = "",
                        cookieSourceUrl = ""
                    )
                )
            }
        }
        if (markMissing) {
            records.indices.forEach { index ->
                val record = records[index]
                val uri = record.fileUri
                if (
                    record.status == STATUS_COMPLETED &&
                    !uri.isNullOrBlank() &&
                    uri !in availableUris
                ) {
                    records[index] = record.copy(
                        status = STATUS_MISSING,
                        progress = 0,
                        speedBytesPerSecond = 0L,
                        message = "檔案已移動或無法存取",
                        fileSizeBytes = 0L
                    )
                }
            }
        }
        saveRecords(context, records.sortedBy { it.createdAt }.takeLast(300))
        notifyChanged(context)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun saveRecords(context: Context, records: List<VideoDownloadRecord>) {
        val array = JSONArray()
        records.forEach { array.put(toJson(it)) }
        prefs(context).edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private fun notifyChanged(context: Context) {
        context.sendBroadcast(Intent(ACTION_DOWNLOADS_CHANGED).setPackage(context.packageName))
    }

    private fun toJson(record: VideoDownloadRecord) = JSONObject().apply {
        put("id", record.id)
        put("title", record.title)
        put("sourceUrl", record.sourceUrl)
        put("status", record.status)
        put("progress", record.progress)
        put("speedBytesPerSecond", record.speedBytesPerSecond)
        put("message", record.message)
        put("fileName", record.fileName)
        put("fileUri", record.fileUri)
        put("fileSizeBytes", record.fileSizeBytes)
        put("javCode", record.javCode)
        put("mimeType", record.mimeType)
        put("storageLocation", record.storageLocation)
        put("createdAt", record.createdAt)
        put("completedAt", record.completedAt)
        put("referer", record.referer)
        put("cookieSourceUrl", record.cookieSourceUrl)
    }

    private fun fromJson(json: JSONObject) = VideoDownloadRecord(
        id = json.optString("id"),
        title = json.optString("title", "video"),
        sourceUrl = json.optString("sourceUrl"),
        status = json.optString("status", STATUS_FAILED),
        progress = json.optInt("progress", 0),
        speedBytesPerSecond = json.optLong("speedBytesPerSecond", 0L),
        message = json.optString("message"),
        fileName = json.optString("fileName").takeIf { it.isNotBlank() && it != "null" },
        fileUri = json.optString("fileUri").takeIf { it.isNotBlank() && it != "null" },
        fileSizeBytes = json.optLong("fileSizeBytes", 0L),
        javCode = json.optString("javCode").takeIf { it.isNotBlank() && it != "null" },
        mimeType = json.optString("mimeType").takeIf { it.isNotBlank() && it != "null" },
        storageLocation = json.optString("storageLocation", "系統下載/JAV Browser"),
        createdAt = json.optLong("createdAt", 0L),
        completedAt = json.optLong("completedAt").takeIf { it > 0L },
        referer = json.optString("referer"),
        cookieSourceUrl = json.optString("cookieSourceUrl")
    )
}
