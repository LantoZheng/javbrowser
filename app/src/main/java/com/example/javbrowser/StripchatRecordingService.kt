package com.example.javbrowser

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import java.io.FileDescriptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StripchatRecordingService : Service() {

    companion object {
        const val ACTION_START = "com.example.javbrowser.STRIPCHAT_RECORD_START"
        const val ACTION_STOP = "com.example.javbrowser.STRIPCHAT_RECORD_STOP"
        const val ACTION_STATE = "com.example.javbrowser.STRIPCHAT_RECORD_STATE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SOURCE_URL = "source_url"
        const val EXTRA_ACTIVE = "active"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_STOP_REASON = "stop_reason"

        @Volatile
        var isRecording: Boolean = false
            private set

        private const val CHANNEL_ID = "stripchat_recording"
        private const val NOTIFICATION_ID = 4108
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outputUri: Uri? = null
    private var outputDescriptor: android.os.ParcelFileDescriptor? = null
    private var downloadRecordId: String? = null
    private var fileName: String = ""
    private var startedAt = 0L
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording(
                    saveFile = true,
                    completionReason = intent.getStringExtra(EXTRA_STOP_REASON).orEmpty(),
                )
                stopSelf()
            }
            ACTION_START -> {
                if (!isRecording) startRecording(intent)
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        startProjectionForeground(buildNotification("準備錄製 Stripchat…"))
        try {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val resultData = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            } ?: error("缺少螢幕錄製授權")
            if (resultCode != Activity.RESULT_OK) error("螢幕錄製未授權")

            val sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL).orEmpty()
            val portrait = resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val width = if (portrait) 1080 else 1920
            val height = if (portrait) 1920 else 1080
            val density = resources.displayMetrics.densityDpi

            fileName = buildFileName(sourceUrl)
            outputUri = createPendingVideo(fileName)
            outputDescriptor = contentResolver.openFileDescriptor(outputUri!!, "rw")
                ?: error("無法建立錄影檔案")

            recorder = createRecorder(outputDescriptor!!.fileDescriptor, width, height)
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopRecording(saveFile = true)
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))

            virtualDisplay = projection?.createVirtualDisplay(
                "JAV Browser Stripchat Recorder",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder!!.surface,
                null,
                null
            ) ?: error("無法建立錄影畫面")

            downloadRecordId = DownloadRepository.create(
                this,
                fileName.substringBeforeLast('.'),
                sourceUrl = "screen-recording:$sourceUrl",
                referer = sourceUrl,
                cookieSourceUrl = sourceUrl
            ).id
            downloadRecordId?.let { id ->
                DownloadRepository.update(this, id) {
                    it.copy(
                        status = DownloadRepository.STATUS_DOWNLOADING,
                        progress = 0,
                        message = "直播錄製中",
                        fileName = fileName,
                        fileUri = outputUri.toString(),
                        mimeType = "video/mp4"
                    )
                }
            }

            recorder!!.start()
            startedAt = System.currentTimeMillis()
            isRecording = true
            updateNotification("Stripchat 錄製中 · 1080p")
            broadcastState(true, "Stripchat 直播錄製中")
        } catch (e: Exception) {
            android.util.Log.e("StripchatRecorder", "start failed", e)
            stopRecording(saveFile = false, failureMessage = e.message ?: "錄製啟動失敗")
            stopSelf()
        }
    }

    private fun createRecorder(fd: FileDescriptor, width: Int, height: Int): MediaRecorder {
        val mediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return mediaRecorder.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(fd)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(8_000_000)
            setVideoFrameRate(30)
            setVideoSize(width, height)
            prepare()
        }
    }

    @Synchronized
    private fun stopRecording(
        saveFile: Boolean,
        failureMessage: String? = null,
        completionReason: String = "",
    ) {
        if (stopping) return
        stopping = true
        val wasRecording = isRecording
        isRecording = false
        var completed = saveFile && wasRecording && System.currentTimeMillis() - startedAt >= 1000L
        try {
            if (wasRecording) recorder?.stop()
        } catch (e: Exception) {
            completed = false
            android.util.Log.w("StripchatRecorder", "stop failed", e)
        }
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        val activeProjection = projection
        projection = null
        runCatching { activeProjection?.stop() }
        runCatching { outputDescriptor?.close() }
        outputDescriptor = null

        val uri = outputUri
        if (completed && uri != null) {
            if (Build.VERSION.SDK_INT >= 29) {
                contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
            }
            val size = queryFileSize(uri)
            downloadRecordId?.let { id ->
                DownloadRepository.update(this, id) {
                    it.copy(
                        status = DownloadRepository.STATUS_COMPLETED,
                        progress = 100,
                        message = when {
                            completionReason.contains("私人秀") -> "公開直播已結束 · 錄製完成"
                            completionReason.contains("下播") -> "主播已下播 · 錄製完成"
                            else -> "直播錄製完成"
                        },
                        fileName = fileName,
                        fileUri = uri.toString(),
                        fileSizeBytes = size,
                        mimeType = "video/mp4",
                        completedAt = System.currentTimeMillis()
                    )
                }
            }
            broadcastState(
                false,
                if (completionReason.isBlank()) "錄製完成：$fileName"
                else "錄製完成：$fileName（$completionReason）",
            )
        } else {
            if (uri != null) runCatching { contentResolver.delete(uri, null, null) }
            downloadRecordId?.let { id ->
                DownloadRepository.update(this, id) {
                    it.copy(
                        status = DownloadRepository.STATUS_FAILED,
                        progress = 0,
                        message = failureMessage ?: "錄製時間太短或錄製失敗"
                    )
                }
            }
            broadcastState(false, failureMessage ?: "錄製未儲存")
        }
        outputUri = null
        downloadRecordId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopping = false
    }

    override fun onDestroy() {
        if (isRecording || outputUri != null) stopRecording(saveFile = true)
        super.onDestroy()
    }

    private fun createPendingVideo(name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/JAV Browser")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        return contentResolver.insert(collection, values) ?: error("無法寫入下載資料夾")
    }

    private fun queryFileSize(uri: Uri): Long {
        return contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        } ?: 0L
    }

    private fun buildFileName(sourceUrl: String): String {
        val model = runCatching { Uri.parse(sourceUrl).path.orEmpty().trim('/').substringBefore('/') }
            .getOrDefault("")
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .ifBlank { "live" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "Stripchat-$model-$stamp.mp4"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Stripchat 直播錄製", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, StripchatRecordingService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("JAV Browser 直播錄製")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "停止並儲存", stopPending)
            .build()
    }

    private fun startProjectionForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun broadcastState(active: Boolean, message: String) {
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_ACTIVE, active)
                .putExtra(EXTRA_MESSAGE, message)
        )
    }
}
