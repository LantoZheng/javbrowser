package com.example.javbrowser

import android.content.Context
import android.content.Intent
import android.net.Uri

object PcRecordingFolderStore {
    private const val PREFS = "pc_recording_library"
    private const val KEY_TREE_URI = "tree_uri"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_LAST_SCAN = "last_scan"

    data class FolderSelection(
        val treeUri: Uri,
        val displayName: String,
        val lastScanAt: Long
    )

    fun read(context: Context): FolderSelection? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rawUri = prefs.getString(KEY_TREE_URI, null)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            FolderSelection(
                treeUri = Uri.parse(rawUri),
                displayName = prefs.getString(KEY_DISPLAY_NAME, "PC 錄影").orEmpty(),
                lastScanAt = prefs.getLong(KEY_LAST_SCAN, 0L)
            )
        }.getOrNull()
    }

    fun save(context: Context, treeUri: Uri, displayName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TREE_URI, treeUri.toString())
            .putString(KEY_DISPLAY_NAME, displayName)
            .putLong(KEY_LAST_SCAN, 0L)
            .apply()
    }

    fun markScanned(context: Context, at: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_SCAN, at)
            .apply()
    }

    fun clear(context: Context) {
        val existing = read(context)
        if (existing != null) {
            runCatching {
                val permission = context.contentResolver.persistedUriPermissions
                    .firstOrNull { it.uri == existing.treeUri }
                var flags = 0
                if (permission?.isReadPermission == true) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (permission?.isWritePermission == true) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                if (flags != 0) {
                    context.contentResolver.releasePersistableUriPermission(existing.treeUri, flags)
                }
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
