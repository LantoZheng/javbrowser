package com.example.javbrowser

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object PcRecordingScanner {
    private val supportedVideoExtensions = setOf("webm", "mp4")
    private val datePattern = Regex("(\\d{8})-(\\d{6})(?=\\.[^.]+$)", RegexOption.IGNORE_CASE)

    data class ScanResult(
        val recordings: List<PcRecordingItem>,
        val permissionLost: Boolean = false
    )

    fun scan(
        context: Context,
        treeUri: Uri,
        cancelled: AtomicBoolean,
        onProgress: (Int) -> Unit = {}
    ): ScanResult {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return ScanResult(emptyList(), permissionLost = true)
        if (!root.exists() || !root.canRead()) return ScanResult(emptyList(), permissionLost = true)

        val result = mutableListOf<PcRecordingItem>()
        var visitedVideos = 0

        fun visit(directory: DocumentFile, depth: Int) {
            if (cancelled.get() || depth > 64) return
            val children = runCatching { directory.listFiles().toList() }.getOrDefault(emptyList())
            val fileIndex = children.filter { it.isFile && !it.name.isNullOrBlank() }
                .associateBy { it.name!!.lowercase(Locale.ROOT) }
            children.filter { it.isDirectory }.forEach { visit(it, depth + 1) }
            if (cancelled.get()) return

            children.filter { file ->
                file.isFile && file.length() > 0L && isSupportedVideo(file.name.orEmpty())
            }.forEach { video ->
                if (cancelled.get()) return
                val fileName = video.name.orEmpty()
                if (fileName.startsWith('.')) return@forEach
                val baseName = fileName.substringBeforeLast('.', fileName)
                val events = fileIndex[(fileName + ".events.json").lowercase(Locale.ROOT)]
                val contact = sequenceOf("_contact.jpg", "_contact.jpeg", "_contact.png")
                    .mapNotNull { suffix -> fileIndex[(baseName + suffix).lowercase(Locale.ROOT)] }
                    .firstOrNull()
                val parsedEvents = events?.let { RecordingEventParser.parse(context, it.uri) }
                result += PcRecordingItem(
                    videoUri = video.uri,
                    fileName = fileName,
                    channelName = directory.name?.takeIf { it.isNotBlank() }
                        ?: root.name?.takeIf { it.isNotBlank() }
                        ?: "PC Recording",
                    recordedAt = parseRecordedAt(fileName) ?: video.lastModified(),
                    sizeBytes = video.length(),
                    durationMs = readDuration(context, video.uri),
                    contactSheetUri = contact?.uri,
                    eventsUri = events?.uri,
                    eventCount = parsedEvents?.document?.events?.size ?: 0,
                    eventsDamaged = parsedEvents?.damaged == true,
                    lastModified = video.lastModified()
                )
                visitedVideos++
                if (visitedVideos == 1 || visitedVideos % 10 == 0) onProgress(visitedVideos)
            }
        }

        visit(root, 0)
        return ScanResult(result)
    }

    private fun isSupportedVideo(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        if (lower.endsWith(".partial") || lower.endsWith(".tmp") || lower.endsWith(".events.json")) {
            return false
        }
        return lower.substringAfterLast('.', "") in supportedVideoExtensions
    }

    private fun parseRecordedAt(fileName: String): Long? {
        val match = datePattern.find(fileName) ?: return null
        val text = match.groupValues[1] + "-" + match.groupValues[2]
        return runCatching {
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { isLenient = false }.parse(text)?.time
        }.getOrNull()
    }

    private fun readDuration(context: Context, uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
