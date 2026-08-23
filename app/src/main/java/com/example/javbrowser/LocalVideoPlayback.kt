package com.example.javbrowser

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object LocalVideoPlayback {
    fun openExternal(activity: Activity, uri: Uri) {
        launchExternal(activity, uri)
    }

    private fun launchExternal(activity: Activity, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            clipData = ClipData.newRawUri("video", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            activity.startActivity(Intent.createChooser(intent, LanguageManager.text(activity, "選擇播放器", "Choose player")))
        }.onFailure {
            Toast.makeText(activity, LanguageManager.text(activity, "找不到可播放此檔案的應用程式", "No app can play this file"), Toast.LENGTH_LONG).show()
        }
    }
}
