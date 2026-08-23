package com.example.javbrowser

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale

data class LocalVideoFile(
    val uri: Uri,
    val fileName: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val mimeType: String,
    val javCode: String?,
    val storageLocation: String = "既有下載檔案"
)

object LocalVideoRepository {
    private val videoExtensions = setOf("mp4", "ts", "mkv", "m4v", "webm", "avi", "mov")

    fun scan(context: Context): List<LocalVideoFile> {
        val files = linkedMapOf<String, LocalVideoFile>()
        val managedRecords = DownloadRepository.list(context)
            .filter(DownloadRepository::isManagedRecord)
        managedRecords
            .filter { it.status == DownloadRepository.STATUS_COMPLETED && !it.fileUri.isNullOrBlank() }
            .forEach { record ->
                inspectUri(context, Uri.parse(record.fileUri), record.fileName)?.let {
                    files[it.uri.toString()] = it.copy(storageLocation = record.storageLocation)
                }
            }

        scanDefaultDownloads(context).forEach { files[it.uri.toString()] = it }
        val managedFileNames = managedRecords.mapNotNull { it.fileName }.toSet()
        scanVideoLibrary(context, managedFileNames).forEach { file ->
            val duplicate = files.values.any { existing ->
                existing.fileName == file.fileName &&
                    (existing.sizeBytes <= 0L || file.sizeBytes <= 0L || existing.sizeBytes == file.sizeBytes)
            }
            if (!duplicate) files[file.uri.toString()] = file
        }
        DownloadRepository.storageTreeUri(context)?.let { treeUri ->
            DocumentFile.fromTreeUri(context, treeUri)?.let { root ->
                scanDocumentTree(
                    root,
                    files,
                    depth = 0,
                    storageLocation = "自訂：${root.name ?: "選擇的資料夾"}"
                )
            }
        }
        return files.values.sortedByDescending { it.modifiedAt }
    }

    fun extractJavCode(value: String): String? {
        val normalized = value.uppercase(Locale.US).replace('_', '-')
        val match = Regex(
            "(?:^|[^A-Z0-9])(" +
                "FC2-PPV-\\d{5,9}|" +
                "[A-Z0-9]{2,10}-\\d{1,5}(?:-\\d{2,5})?|" +
                "[A-Z]{2,8}\\d{2,7}" +
                ")(?=$|[^A-Z0-9])"
        ).find(normalized) ?: return null
        val raw = match.groupValues[1]
        if (raw.none(Char::isLetter)) return null
        if ('-' in raw) return JavDbScraper.normalizeJavCode(raw)
        val compact = Regex("^([A-Z]{2,8})(\\d{2,7})$").find(raw) ?: return null
        return JavDbScraper.normalizeJavCode("${compact.groupValues[1]}-${compact.groupValues[2]}")
    }

    fun normalizeCode(code: String?): String? = code
        ?.takeIf { it.isNotBlank() }
        ?.let(JavDbScraper::normalizeJavCode)

    fun querySize(context: Context, uri: Uri): Long = inspectUri(context, uri, null)?.sizeBytes ?: 0L

    fun hasVideoLibraryAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) ==
                PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                    ) == PackageManager.PERMISSION_GRANTED)
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        else -> true
    }

    fun importUris(context: Context, uris: Collection<Uri>): List<LocalVideoFile> = uris
        .distinctBy(Uri::toString)
        .mapNotNull { uri ->
            inspectUri(context, uri, null)?.copy(storageLocation = "匯入影片")
        }

    private fun inspectUri(context: Context, uri: Uri, fallbackName: String?): LocalVideoFile? {
        var name = fallbackName.orEmpty()
        var size = 0L
        var modified = 0L
        var mime = context.contentResolver.getType(uri).orEmpty()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File) ?: return null
            if (!file.exists()) return null
            name = file.name
            size = file.length()
            modified = file.lastModified()
            mime = mime.ifBlank { mimeForName(name) }
        } else {
            val canRead = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    if (size <= 0L) size = descriptor.statSize.coerceAtLeast(0L)
                }
                true
            }.getOrDefault(false)
            if (!canRead) return null
        }
        if (name.isBlank() || !isVideo(name, mime)) return null
        return LocalVideoFile(uri, name, size, modified, mime.ifBlank { mimeForName(name) }, extractJavCode(name))
    }

    private fun scanDefaultDownloads(context: Context): List<LocalVideoFile> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "JAV Browser")
            return directory.listFiles().orEmpty().mapNotNull { file ->
                if (!file.isFile || !isVideo(file.name, mimeForName(file.name))) return@mapNotNull null
                LocalVideoFile(
                    Uri.fromFile(file),
                    file.name,
                    file.length(),
                    file.lastModified(),
                    mimeForName(file.name),
                    extractJavCode(file.name),
                    "系統下載/JAV Browser"
                )
            }
        }

        val result = mutableListOf<LocalVideoFile>()
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED,
            MediaStore.Downloads.MIME_TYPE
        )
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("${Environment.DIRECTORY_DOWNLOADS}/JAV Browser/%"),
                "${MediaStore.Downloads.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn) ?: continue
                    val mime = cursor.getString(mimeColumn).orEmpty()
                    if (!isVideo(name, mime)) continue
                    result.add(
                        LocalVideoFile(
                            ContentUris.withAppendedId(collection, cursor.getLong(idColumn)),
                            name,
                            cursor.getLong(sizeColumn).coerceAtLeast(0L),
                            cursor.getLong(modifiedColumn) * 1000L,
                            mime.ifBlank { mimeForName(name) },
                            extractJavCode(name),
                            "系統下載/JAV Browser"
                        )
                    )
                }
            }
        }
        return result
    }

    private fun scanVideoLibrary(
        context: Context,
        managedFileNames: Set<String>
    ): List<LocalVideoFile> {
        if (!hasVideoLibraryAccess(context) || managedFileNames.isEmpty()) return emptyList()
        val result = mutableListOf<LocalVideoFile>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.Video.Media.DISPLAY_NAME)
            add(MediaStore.Video.Media.SIZE)
            add(MediaStore.Video.Media.DATE_MODIFIED)
            add(MediaStore.Video.Media.MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Video.Media.RELATIVE_PATH)
            }
        }.toTypedArray()
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                } else {
                    -1
                }
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn) ?: continue
                    if (name !in managedFileNames) continue
                    val mime = cursor.getString(mimeColumn).orEmpty()
                    if (!isVideo(name, mime)) continue
                    val relativePath = if (pathColumn >= 0) {
                        cursor.getString(pathColumn).orEmpty().trimEnd('/')
                    } else {
                        ""
                    }
                    result.add(
                        LocalVideoFile(
                            ContentUris.withAppendedId(collection, cursor.getLong(idColumn)),
                            name,
                            cursor.getLong(sizeColumn).coerceAtLeast(0L),
                            cursor.getLong(modifiedColumn) * 1000L,
                            mime.ifBlank { mimeForName(name) },
                            extractJavCode(name),
                            if (relativePath.isBlank()) "影片媒體庫" else "媒體庫：$relativePath"
                        )
                    )
                }
            }
        }
        return result
    }

    private fun scanDocumentTree(
        directory: DocumentFile,
        output: MutableMap<String, LocalVideoFile>,
        depth: Int,
        storageLocation: String
    ) {
        if (depth > 8) return
        directory.listFiles().forEach { document ->
            if (document.isDirectory) {
                scanDocumentTree(document, output, depth + 1, storageLocation)
            } else {
                val name = document.name ?: return@forEach
                val mime = document.type.orEmpty()
                if (!isVideo(name, mime)) return@forEach
                val file = LocalVideoFile(
                    document.uri,
                    name,
                    document.length().coerceAtLeast(0L),
                    document.lastModified(),
                    mime.ifBlank { mimeForName(name) },
                    extractJavCode(name),
                    storageLocation
                )
                output[file.uri.toString()] = file
            }
        }
    }

    private fun isVideo(name: String, mimeType: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
        return mimeType.startsWith("video/") || extension in videoExtensions
    }

    private fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "ts" -> "video/mp2t"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        else -> "video/mp4"
    }
}
