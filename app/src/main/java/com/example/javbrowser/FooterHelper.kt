package com.example.javbrowser

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity

object FooterHelper {
    fun setup(activity: AppCompatActivity) {
        activity.findViewById<android.widget.TextView>(R.id.tv_footer_github)?.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://bit.ly/3O6GVOM"))
            activity.startActivity(intent)
        }
    }
}
