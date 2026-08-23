package com.example.javbrowser

import android.content.Context
import android.content.SharedPreferences

class PrivacySettings(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("privacy_prefs", Context.MODE_PRIVATE)
    
    companion object {
        private const val KEY_LOCK_ENABLED = "lock_enabled"
        private const val KEY_SELECTED_ICON = "selected_icon"
        private const val KEY_LAST_UNLOCK_TIME = "last_unlock_time"
        private const val KEY_SCREEN_SECURE = "screen_secure"
        private const val KEY_DOUBLE_TAP_SEEK_SECONDS = "double_tap_seek_seconds"
        private const val KEY_INTERNAL_PLAYER_MUTED = "internal_player_muted"
        private const val KEY_ALWAYS_USE_INTERNAL_PLAYER = "always_use_internal_player"
        private const val KEY_PLAYER_SHORT_SEEK_SECONDS = "player_short_seek_seconds"
        private const val KEY_PLAYER_LONG_SEEK_SECONDS = "player_long_seek_seconds"
        private const val KEY_PLAYBACK_SPEED_OPTIONS = "playback_speed_options"
        private const val KEY_PRESS_HOLD_PLAYBACK_RATE = "press_hold_playback_rate"
        private const val DEFAULT_PLAYBACK_SPEED_OPTIONS = "0.75,1,1.5,2,3,6,12,16"
        
        const val ICON_DEFAULT = "default"
        const val ICON_CALCULATOR = "calculator"
        const val ICON_NOTES = "notes"
        const val ICON_FILE = "file"

        fun formatPlaybackRate(rate: Double): String {
            return if (rate % 1.0 == 0.0) rate.toInt().toString() else rate.toString().trimEnd('0').trimEnd('.')
        }
    }
    
    var isLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCK_ENABLED, value).apply()

    /** 防截圖 / 防投屏，預設開啟 */
    var isScreenSecure: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_SECURE, true)
        set(value) = prefs.edit().putBoolean(KEY_SCREEN_SECURE, value).apply()

    var doubleTapSeekSeconds: Int
        get() = prefs.getInt(KEY_DOUBLE_TAP_SEEK_SECONDS, 30)
        set(value) = prefs.edit().putInt(KEY_DOUBLE_TAP_SEEK_SECONDS, value.coerceIn(5, 300)).apply()

    var internalPlayerMuted: Boolean
        get() = prefs.getBoolean(KEY_INTERNAL_PLAYER_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_INTERNAL_PLAYER_MUTED, value).apply()

    var alwaysUseInternalPlayer: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_USE_INTERNAL_PLAYER, false)
        set(value) = prefs.edit().putBoolean(KEY_ALWAYS_USE_INTERNAL_PLAYER, value).apply()

    var playerShortSeekSeconds: Int
        get() = prefs.getInt(KEY_PLAYER_SHORT_SEEK_SECONDS, 20)
        set(value) = prefs.edit().putInt(KEY_PLAYER_SHORT_SEEK_SECONDS, value.coerceIn(1, 3600)).apply()

    var playerLongSeekSeconds: Int
        get() = prefs.getInt(KEY_PLAYER_LONG_SEEK_SECONDS, 60)
        set(value) = prefs.edit().putInt(KEY_PLAYER_LONG_SEEK_SECONDS, value.coerceIn(1, 3600)).apply()

    var playbackSpeedOptions: List<Double>
        get() = normalizePlaybackSpeedOptions(
            prefs.getString(KEY_PLAYBACK_SPEED_OPTIONS, DEFAULT_PLAYBACK_SPEED_OPTIONS)
                ?.split(',')
                ?.mapNotNull { it.trim().toDoubleOrNull() }
                .orEmpty()
        )
        set(value) {
            val normalized = normalizePlaybackSpeedOptions(value)
            val stored = normalized.joinToString(",") { formatPlaybackRate(it) }
            prefs.edit().putString(KEY_PLAYBACK_SPEED_OPTIONS, stored).apply()
        }

    var pressHoldPlaybackRate: Double
        get() = prefs.getString(KEY_PRESS_HOLD_PLAYBACK_RATE, "3")
            ?.toDoubleOrNull()?.coerceIn(1.25, 16.0) ?: 3.0
        set(value) = prefs.edit()
            .putString(KEY_PRESS_HOLD_PLAYBACK_RATE, formatPlaybackRate(value.coerceIn(1.25, 16.0)))
            .apply()

    private fun normalizePlaybackSpeedOptions(values: List<Double>): List<Double> {
        val valid = values.filter { it.isFinite() && it in 0.25..16.0 && it != 1.0 }
            .distinct()
            .take(11)
            .toMutableList()
        valid.add(1.0)
        return valid.sorted().ifEmpty {
            DEFAULT_PLAYBACK_SPEED_OPTIONS.split(',').map { it.toDouble() }
        }
    }
    
    var selectedIcon: String
        get() = prefs.getString(KEY_SELECTED_ICON, ICON_DEFAULT) ?: ICON_DEFAULT
        set(value) = prefs.edit().putString(KEY_SELECTED_ICON, value).apply()
    
    var lastUnlockTime: Long
        get() = prefs.getLong(KEY_LAST_UNLOCK_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UNLOCK_TIME, value).apply()
    
    fun shouldLock(): Boolean {
        if (!isLockEnabled) return false
        
        val currentTime = System.currentTimeMillis()
        val oneHourMillis = 60 * 60 * 1000L // 1 hour in milliseconds
        
        return (currentTime - lastUnlockTime) > oneHourMillis
    }
    
    fun updateUnlockTime() {
        lastUnlockTime = System.currentTimeMillis()
    }

    // PIN Code Support
    var pinCode: String?
        get() = prefs.getString("app_pin_code", null)
        set(value) = prefs.edit().putString("app_pin_code", value).apply()

    fun isPinSet(): Boolean {
        return !pinCode.isNullOrEmpty()
    }

    fun validatePin(inputPin: String): Boolean {
        return inputPin == pinCode
    }
    
    // Get current icon resource ID based on selected icon
    val currentIconResourceId: Int
        get() = when (selectedIcon) {
            ICON_CALCULATOR -> R.drawable.ic_launcher_calculator
            ICON_NOTES -> R.drawable.ic_launcher_notes
            ICON_FILE -> R.drawable.ic_launcher_file
            else -> R.drawable.ic_launcher  // Default
        }
    
    // Get current app label based on selected icon
    val currentAppLabel: String
        get() = when (selectedIcon) {
            ICON_CALCULATOR -> "Calculator"
            ICON_NOTES -> "Notes"
            ICON_FILE -> "File Manager"
            else -> "JAV Browser"  // Default
        }
}
