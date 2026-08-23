package com.example.javbrowser

import android.content.ClipData
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PcRecordingLibraryActivity : LocalizedActivity() {
    private lateinit var adapter: PcRecordingAdapter
    private lateinit var folderLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var emptyLabel: TextView
    private lateinit var progress: ProgressBar
    private lateinit var chooseButton: Button
    private lateinit var rescanButton: Button
    private lateinit var removeButton: Button
    private val executor = Executors.newSingleThreadExecutor()
    private val scanGeneration = AtomicInteger(0)
    private var scanCancel = AtomicBoolean(false)
    private var currentRecordings: List<PcRecordingItem> = emptyList()
    private val collapsedChannels = mutableSetOf<String>()

    private val chooseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val name = DocumentFile.fromTreeUri(this, uri)?.name
                ?: uri.lastPathSegment
                ?: LanguageManager.text(this, "PC 錄影", "PC Recordings")
            PcRecordingCache.clear(this)
            PcRecordingFolderStore.save(this, uri, name)
            updateFolderHeader()
            scanSelectedFolder()
        }.onFailure {
            Toast.makeText(
                this,
                LanguageManager.text(
                    this,
                    "無法保存錄影資料夾的讀寫權限",
                    "Unable to retain read and write access to this folder"
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PrivacySettings(this).isScreenSecure) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        setContentView(R.layout.activity_pc_recording_library)
        folderLabel = findViewById(R.id.tv_pc_recording_folder)
        statusLabel = findViewById(R.id.tv_pc_recording_status)
        emptyLabel = findViewById(R.id.tv_pc_recording_empty)
        progress = findViewById(R.id.progress_pc_recording_scan)
        chooseButton = findViewById(R.id.btn_pc_recording_choose)
        rescanButton = findViewById(R.id.btn_pc_recording_rescan)
        removeButton = findViewById(R.id.btn_pc_recording_remove)

        adapter = PcRecordingAdapter(
            onOpen = ::openRecording,
            onToggleChannel = ::toggleChannel
        )
        findViewById<RecyclerView>(R.id.rv_pc_recordings).apply {
            layoutManager = LinearLayoutManager(this@PcRecordingLibraryActivity)
            adapter = this@PcRecordingLibraryActivity.adapter
            setupSwipeToDelete(this)
        }
        findViewById<Button>(R.id.btn_pc_recording_back).setOnClickListener { finish() }
        chooseButton.setOnClickListener {
            chooseFolder.launch(PcRecordingFolderStore.read(this)?.treeUri)
        }
        rescanButton.setOnClickListener { scanSelectedFolder() }
        removeButton.setOnClickListener { confirmRemoveFolder() }
        applyCompactEnglishLayout()
        updateFolderHeader()

        if (PcRecordingFolderStore.read(this) == null) {
            showNoFolder()
        } else {
            loadCachedLibrary()
        }
    }

    override fun onDestroy() {
        scanCancel.set(true)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun scanSelectedFolder() {
        val selection = PcRecordingFolderStore.read(this) ?: run {
            showNoFolder()
            return
        }
        scanCancel.set(true)
        scanCancel = AtomicBoolean(false)
        val localCancel = scanCancel
        val generation = scanGeneration.incrementAndGet()
        setScanning(true)
        statusLabel.text = LanguageManager.text(this, "正在掃描錄影資料夾…", "Scanning recording folder…")

        executor.execute {
            val result = PcRecordingScanner.scan(this, selection.treeUri, localCancel) { count ->
                runOnUiThread {
                    if (generation == scanGeneration.get()) {
                        statusLabel.text = LanguageManager.text(
                            this,
                            "正在掃描，已找到 $count 個影片…",
                            "Scanning… $count videos found"
                        )
                    }
                }
            }
            if (localCancel.get()) return@execute
            if (!result.permissionLost) {
                runCatching { PcRecordingCache.save(this, selection.treeUri, result.recordings) }
            }
            runOnUiThread {
                if (generation != scanGeneration.get()) return@runOnUiThread
                setScanning(false)
                if (result.permissionLost) {
                    PcRecordingCache.clear(this)
                    currentRecordings = emptyList()
                    adapter.submit(emptyList())
                    emptyLabel.visibility = View.VISIBLE
                    emptyLabel.text = LanguageManager.text(
                        this,
                        "錄影資料夾存取權已失效，請重新選擇資料夾",
                        "Recording folder access has expired. Please select it again."
                    )
                    statusLabel.text = emptyLabel.text
                    return@runOnUiThread
                }
                PcRecordingFolderStore.markScanned(this)
                showRecordings(result.recordings, fromCache = false)
                updateFolderHeader()
            }
        }
    }

    private fun loadCachedLibrary() {
        val selection = PcRecordingFolderStore.read(this) ?: run {
            showNoFolder()
            return
        }
        setScanning(false)
        statusLabel.text = LanguageManager.text(this, "正在載入錄影庫快取…", "Loading recording library cache…")
        executor.execute {
            val snapshot = PcRecordingCache.load(this, selection.treeUri)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (snapshot == null) {
                    currentRecordings = emptyList()
                    adapter.submit(emptyList())
                    emptyLabel.visibility = View.VISIBLE
                    emptyLabel.text = LanguageManager.text(
                        this,
                        "尚未建立錄影庫快取，請按「重新掃描」",
                        "No recording cache yet. Tap Rescan to create it."
                    )
                    statusLabel.text = LanguageManager.text(
                        this,
                        "等待手動重新掃描，不會自動讀取資料夾",
                        "Waiting for a manual rescan; the folder will not be scanned automatically"
                    )
                } else {
                    showRecordings(snapshot.recordings, fromCache = true)
                }
                updateFolderHeader()
            }
        }
    }

    private fun showRecordings(recordings: List<PcRecordingItem>, fromCache: Boolean) {
        currentRecordings = recordings
        adapter.submit(buildRows(currentRecordings))
        emptyLabel.visibility = if (recordings.isEmpty()) View.VISIBLE else View.GONE
        emptyLabel.text = LanguageManager.text(
            this,
            if (fromCache) "快取中沒有影片，請按「重新掃描」更新" else "資料夾內沒有可用的 WebM 或 MP4 影片",
            if (fromCache) "No videos in the cache. Tap Rescan to update it." else "No supported WebM or MP4 videos were found"
        )
        val damaged = recordings.count { it.eventsDamaged }
        statusLabel.text = LanguageManager.text(
            this,
            "共 ${recordings.size} 個影片・${recordings.sumOf { it.eventCount }} 個贊助標記" +
                (if (damaged > 0) "・$damaged 個事件檔損壞" else "") +
                (if (fromCache) "・快取" else ""),
            "${recordings.size} videos · ${recordings.sumOf { it.eventCount }} tip markers" +
                (if (damaged > 0) " · $damaged damaged event files" else "") +
                (if (fromCache) " · Cached" else "")
        )
    }

    private fun setScanning(scanning: Boolean) {
        progress.visibility = if (scanning) View.VISIBLE else View.GONE
        rescanButton.isEnabled = !scanning
        chooseButton.isEnabled = !scanning
        removeButton.isEnabled = !scanning
    }

    private fun showNoFolder() {
        currentRecordings = emptyList()
        collapsedChannels.clear()
        adapter.submit(emptyList())
        setScanning(false)
        folderLabel.text = LanguageManager.text(this, "尚未選擇 PC 錄影資料夾", "No PC recording folder selected")
        statusLabel.text = LanguageManager.text(
            this,
            "選擇從電腦複製到手機或 SD 卡的錄影根目錄",
            "Select the recording root copied from the PC to this phone or SD card"
        )
        emptyLabel.visibility = View.VISIBLE
        emptyLabel.text = LanguageManager.text(this, "請先選擇錄影資料夾", "Choose a recording folder to begin")
        rescanButton.isEnabled = false
        removeButton.isEnabled = false
    }

    private fun updateFolderHeader() {
        val selection = PcRecordingFolderStore.read(this)
        if (selection == null) {
            folderLabel.text = LanguageManager.text(this, "尚未選擇 PC 錄影資料夾", "No PC recording folder selected")
            return
        }
        val lastScan = if (selection.lastScanAt > 0L) {
            SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(selection.lastScanAt))
        } else {
            LanguageManager.text(this, "尚未掃描", "Not scanned yet")
        }
        folderLabel.text = LanguageManager.text(
            this,
            "📁 ${selection.displayName}・上次掃描 $lastScan",
            "📁 ${selection.displayName} · Last scan $lastScan"
        )
        rescanButton.isEnabled = true
        removeButton.isEnabled = true
    }

    private fun confirmRemoveFolder() {
        AlertDialog.Builder(this)
            .setTitle(LanguageManager.text(this, "移除錄影資料夾？", "Remove recording folder?"))
            .setMessage(
                LanguageManager.text(
                    this,
                    "只會移除 App 的資料夾紀錄，不會刪除手機或 SD 卡上的任何檔案。",
                    "This only removes the folder from the app. No files will be deleted."
                )
            )
            .setNegativeButton(LanguageManager.text(this, "取消", "Cancel"), null)
            .setPositiveButton(LanguageManager.text(this, "移除", "Remove")) { _, _ ->
                scanCancel.set(true)
                scanGeneration.incrementAndGet()
                PcRecordingCache.clear(this)
                PcRecordingFolderStore.clear(this)
                showNoFolder()
            }
            .show()
    }

    private fun toggleChannel(channelName: String) {
        if (!collapsedChannels.add(channelName)) collapsedChannels.remove(channelName)
        adapter.submit(buildRows(currentRecordings))
    }

    private fun setupSwipeToDelete(recyclerView: RecyclerView) {
        val density = resources.displayMetrics.density
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C62828")
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14f * resources.displayMetrics.scaledDensity
            textAlign = Paint.Align.RIGHT
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val label = LanguageManager.text(this, "刪除", "Delete")
        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val position = viewHolder.bindingAdapterPosition
                return if (position != RecyclerView.NO_POSITION && adapter.isRecordingPosition(position)) {
                    makeMovementFlags(0, ItemTouchHelper.LEFT)
                } else {
                    makeMovementFlags(0, 0)
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.42f

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition.takeIf {
                    it != RecyclerView.NO_POSITION
                } ?: viewHolder.layoutPosition
                val recording = adapter.recordingAt(position)
                if (position != RecyclerView.NO_POSITION) adapter.restoreSwiped(position)
                recording?.let(::confirmDeleteRecording)
            }

            override fun onChildDraw(
                canvas: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val position = viewHolder.bindingAdapterPosition
                if (
                    actionState == ItemTouchHelper.ACTION_STATE_SWIPE &&
                    dX < 0f &&
                    position != RecyclerView.NO_POSITION &&
                    adapter.isRecordingPosition(position)
                ) {
                    val itemView = viewHolder.itemView
                    val background = RectF(
                        itemView.right.toFloat() + dX,
                        itemView.top.toFloat(),
                        itemView.right.toFloat(),
                        itemView.bottom.toFloat()
                    )
                    canvas.drawRoundRect(background, 10f * density, 10f * density, backgroundPaint)
                    val centerY = background.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
                    canvas.drawText(label, itemView.right - 18f * density, centerY, textPaint)
                }
                super.onChildDraw(
                    canvas,
                    recyclerView,
                    viewHolder,
                    dX,
                    dY,
                    actionState,
                    isCurrentlyActive
                )
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recyclerView)
    }

    private fun hasFolderWritePermission(): Boolean {
        val treeUri = PcRecordingFolderStore.read(this)?.treeUri ?: return false
        return contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isWritePermission
        }
    }

    private fun requestFolderWritePermission() {
        AlertDialog.Builder(this)
            .setTitle(LanguageManager.text(this, "需要刪除權限", "Delete access required"))
            .setMessage(
                LanguageManager.text(
                    this,
                    "目前資料夾是舊版的唯讀授權。請重新選擇同一個 PC 錄影資料夾，才能刪除影片。",
                    "This folder still has the older read-only grant. Select the same PC recording folder again to enable deletion."
                )
            )
            .setNegativeButton(LanguageManager.text(this, "取消", "Cancel"), null)
            .setPositiveButton(LanguageManager.text(this, "重新選擇", "Select folder")) { _, _ ->
                chooseFolder.launch(PcRecordingFolderStore.read(this)?.treeUri)
            }
            .show()
    }

    private fun confirmDeleteRecording(item: PcRecordingItem) {
        if (!hasFolderWritePermission()) {
            requestFolderWritePermission()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(LanguageManager.text(this, "永久刪除影片？", "Permanently delete video?"))
            .setMessage(
                LanguageManager.text(
                    this,
                    "將刪除 ${item.fileName}\n大小：${formatBytes(item.sizeBytes)}\n\n此操作無法復原。配對的事件 JSON 與縮圖不會刪除。",
                    "Delete ${item.fileName}\nSize: ${formatBytes(item.sizeBytes)}\n\nThis cannot be undone. The paired event JSON and contact sheet will be kept."
                )
            )
            .setNegativeButton(LanguageManager.text(this, "取消", "Cancel"), null)
            .setPositiveButton(LanguageManager.text(this, "刪除影片", "Delete video")) { _, _ ->
                deleteRecording(item)
            }
            .show()
    }

    private fun deleteRecording(item: PcRecordingItem) {
        setScanning(true)
        statusLabel.text = LanguageManager.text(this, "正在刪除影片…", "Deleting video…")
        val treeUri = PcRecordingFolderStore.read(this)?.treeUri
        executor.execute {
            val deletion = runCatching {
                DocumentFile.fromSingleUri(this, item.videoUri)?.delete() == true
            }
            val remaining = if (deletion.getOrDefault(false)) {
                currentRecordings.filterNot { it.videoUri == item.videoUri }
            } else {
                currentRecordings
            }
            val cacheUpdated = deletion.getOrDefault(false) && treeUri != null && runCatching {
                PcRecordingCache.save(this, treeUri, remaining)
            }.isSuccess
            if (deletion.getOrDefault(false) && !cacheUpdated) {
                PcRecordingCache.clear(this)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (deletion.getOrDefault(false)) {
                    setScanning(false)
                    collapsedChannels.retainAll(remaining.mapTo(mutableSetOf()) { it.channelName })
                    showRecordings(remaining, fromCache = cacheUpdated)
                    updateFolderHeader()
                    Toast.makeText(
                        this,
                        LanguageManager.text(
                            this,
                            if (cacheUpdated) "影片已刪除，清單已更新" else "影片已刪除，但快取更新失敗，請稍後重新掃描",
                            if (cacheUpdated) "Video deleted and the list was updated" else "Video deleted, but the cache update failed. Rescan later."
                        ),
                        if (cacheUpdated) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                    ).show()
                } else {
                    setScanning(false)
                    statusLabel.text = LanguageManager.text(
                        this,
                        "無法刪除影片，請確認資料夾寫入權限",
                        "Unable to delete the video. Check folder write access."
                    )
                    Toast.makeText(this, statusLabel.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openRecording(item: PcRecordingItem) {
        val intent = Intent(this, FullscreenInternalPlayerActivity::class.java).apply {
            putExtra(FullscreenInternalPlayerActivity.EXTRA_VIDEO_URL, item.videoUri.toString())
            putExtra(FullscreenInternalPlayerActivity.EXTRA_LOCAL_PLAYBACK, true)
            putExtra(FullscreenInternalPlayerActivity.EXTRA_DOWNLOAD_NAME, item.fileName)
            putExtra(FullscreenInternalPlayerActivity.EXTRA_PC_RECORDING_EVENTS_URI, item.eventsUri?.toString())
            putExtra(FullscreenInternalPlayerActivity.EXTRA_PC_RECORDING_CONTACT_URI, item.contactSheetUri?.toString())
            putExtra(FullscreenInternalPlayerActivity.EXTRA_PC_RECORDING_DISPLAY_NAME, item.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("PC recording", item.videoUri).also { clip ->
                item.eventsUri?.let { clip.addItem(ClipData.Item(it)) }
                item.contactSheetUri?.let { clip.addItem(ClipData.Item(it)) }
            }
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(
                this,
                LanguageManager.text(this, "影片已被移動或無法存取", "The video was moved or cannot be accessed"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun buildRows(recordings: List<PcRecordingItem>): List<LibraryRow> {
        val dateKey = SimpleDateFormat("yyyyMMdd", Locale.US)
        val dateLabel = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
        val rows = mutableListOf<LibraryRow>()
        recordings.groupBy { it.channelName }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, List<PcRecordingItem>>> {
                it.value.maxOfOrNull(PcRecordingItem::recordedAt) ?: 0L
            }.thenBy { it.key.lowercase(Locale.ROOT) })
            .forEach { channel ->
                val collapsed = channel.key in collapsedChannels
                rows += LibraryRow.Channel(channel.key, channel.value.size, collapsed)
                if (collapsed) return@forEach
                channel.value.groupBy { dateKey.format(Date(it.recordedAt)) }
                    .entries.sortedByDescending { it.key }
                    .forEach { day ->
                        val date = day.value.firstOrNull()?.recordedAt ?: 0L
                        rows += LibraryRow.Day(dateLabel.format(Date(date)), day.value.size)
                        day.value.sortedBy { it.recordedAt }.forEach { rows += LibraryRow.Recording(it) }
                    }
            }
        return rows
    }

    private fun applyCompactEnglishLayout() {
        if (!LanguageManager.isEnglish(this)) return
        findViewById<TextView>(R.id.tv_pc_recording_title).textSize = 18f
        listOf(chooseButton, rescanButton, removeButton).forEach {
            it.isAllCaps = false
            it.textSize = 10f
            it.minWidth = 0
            it.minimumWidth = 0
        }
    }

    private sealed class LibraryRow {
        data class Channel(val name: String, val count: Int, val collapsed: Boolean) : LibraryRow()
        data class Day(val label: String, val count: Int) : LibraryRow()
        data class Recording(val item: PcRecordingItem) : LibraryRow()
    }

    private inner class PcRecordingAdapter(
        private val onOpen: (PcRecordingItem) -> Unit,
        private val onToggleChannel: (String) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val rows = mutableListOf<LibraryRow>()

        fun submit(newRows: List<LibraryRow>) {
            rows.clear()
            rows.addAll(newRows)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is LibraryRow.Channel -> 0
            is LibraryRow.Day -> 1
            is LibraryRow.Recording -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 2) {
                RecordingHolder(inflater.inflate(R.layout.item_pc_recording, parent, false))
            } else {
                HeaderHolder(inflater.inflate(R.layout.item_pc_recording_header, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is LibraryRow.Channel -> (holder as HeaderHolder).bind(
                    label = row.name,
                    count = row.count,
                    channel = true,
                    collapsed = row.collapsed
                )
                is LibraryRow.Day -> (holder as HeaderHolder).bind(
                    label = row.label,
                    count = row.count,
                    channel = false,
                    collapsed = false
                )
                is LibraryRow.Recording -> (holder as RecordingHolder).bind(row.item)
            }
        }

        override fun getItemCount(): Int = rows.size

        fun isRecordingPosition(position: Int): Boolean =
            position in rows.indices && rows[position] is LibraryRow.Recording

        fun recordingAt(position: Int): PcRecordingItem? =
            (rows.getOrNull(position) as? LibraryRow.Recording)?.item

        fun restoreSwiped(position: Int) {
            if (position in rows.indices) notifyItemChanged(position)
        }

        private inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val text: TextView = view.findViewById(R.id.tv_pc_recording_header)
            fun bind(label: String, count: Int, channel: Boolean, collapsed: Boolean) {
                text.text = if (channel) {
                    "${if (collapsed) "▸" else "▾"} $label  ($count)"
                } else {
                    "  $label  ($count)"
                }
                text.textSize = if (channel) 20f else 15f
                text.setTextColor(Color.parseColor(if (channel) "#E1BEE7" else "#BDBDBD"))
                text.setBackgroundColor(Color.parseColor(if (channel) "#2A1538" else "#1B1B1B"))
                if (channel) {
                    text.isClickable = true
                    text.isFocusable = true
                    text.contentDescription = LanguageManager.text(
                        this@PcRecordingLibraryActivity,
                        "$label，${if (collapsed) "展開" else "收合"}影片列表",
                        "$label, ${if (collapsed) "expand" else "collapse"} video list"
                    )
                    text.setOnClickListener { onToggleChannel(label) }
                } else {
                    text.isClickable = false
                    text.isFocusable = false
                    text.contentDescription = null
                    text.setOnClickListener(null)
                }
            }
        }

        private inner class RecordingHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val image: ImageView = view.findViewById(R.id.iv_pc_recording_contact)
            private val title: TextView = view.findViewById(R.id.tv_pc_recording_item_title)
            private val meta: TextView = view.findViewById(R.id.tv_pc_recording_item_meta)
            private val file: TextView = view.findViewById(R.id.tv_pc_recording_item_file)

            fun bind(item: PcRecordingItem) {
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.recordedAt))
                title.text = "$time · ${segmentLabel(item.fileName)}"
                val duration = item.durationMs?.let(::formatDuration)
                    ?: LanguageManager.text(this@PcRecordingLibraryActivity, "時長未知", "Unknown duration")
                val marker = when {
                    item.eventsDamaged -> LanguageManager.text(
                        this@PcRecordingLibraryActivity,
                        "事件資料無法讀取",
                        "Event data unavailable"
                    )
                    else -> LanguageManager.text(
                        this@PcRecordingLibraryActivity,
                        "${item.eventCount} 個贊助標記",
                        "${item.eventCount} tip markers"
                    )
                }
                meta.text = "$duration · ${formatBytes(item.sizeBytes)} · $marker"
                file.text = item.fileName
                image.contentDescription = LanguageManager.text(
                    this@PcRecordingLibraryActivity,
                    "播放 ${item.fileName}",
                    "Play ${item.fileName}"
                )
                Glide.with(image)
                    .clear(image)
                if (item.contactSheetUri != null) {
                    Glide.with(image)
                        .load(item.contactSheetUri)
                        .centerCrop()
                        .placeholder(android.R.drawable.ic_media_play)
                        .error(android.R.drawable.ic_media_play)
                        .into(image)
                } else {
                    image.setImageResource(android.R.drawable.ic_media_play)
                }
                itemView.setOnClickListener { onOpen(item) }
            }
        }
    }

    private fun segmentLabel(fileName: String): String {
        Regex("-merged-(\\d+)", RegexOption.IGNORE_CASE).find(fileName)?.let {
            val count = it.groupValues[1].toIntOrNull() ?: 0
            return LanguageManager.text(this, "合併 $count 段", "Merged $count parts")
        }
        Regex("-part(\\d+)", RegexOption.IGNORE_CASE).find(fileName)?.let {
            return LanguageManager.text(this, "片段 ${it.groupValues[1]}", "Part ${it.groupValues[1]}")
        }
        return LanguageManager.text(this, "錄影", "Recording")
    }

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0L) "%d:%02d:%02d".format(Locale.US, h, m, s)
        else "%d:%02d".format(Locale.US, m, s)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
        else -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
    }
}
