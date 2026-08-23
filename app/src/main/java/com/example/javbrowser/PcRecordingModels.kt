package com.example.javbrowser

import android.net.Uri

data class PcRecordingItem(
    val videoUri: Uri,
    val fileName: String,
    val channelName: String,
    val recordedAt: Long,
    val sizeBytes: Long,
    val durationMs: Long?,
    val contactSheetUri: Uri?,
    val eventsUri: Uri?,
    val eventCount: Int,
    val eventsDamaged: Boolean,
    val lastModified: Long
)

data class RecordingEventDocument(
    val version: Int = 1,
    val videoFileName: String = "",
    val events: List<RecordingTimelineEvent> = emptyList()
)

data class RecordingTimelineEvent(
    val id: String = "",
    val occurredAt: String = "",
    val offsetSeconds: Double = 0.0,
    val username: String = "",
    val tokens: Int = 0,
    val message: String = "",
    val rawText: String = ""
)

data class RecordingEventParseResult(
    val document: RecordingEventDocument = RecordingEventDocument(),
    val damaged: Boolean = false
)
