package dev.cloudwind.app

import android.content.Context

/** Non-secret preferences. Defaults are conservative: Wi-Fi only, new photos only. */
class Settings(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("cloudwind_settings", Context.MODE_PRIVATE)

    var wifiOnly: Boolean
        get() = sp.getBoolean("wifi_only", true)
        set(v) = sp.edit().putBoolean("wifi_only", v).apply()

    var chargingOnly: Boolean
        get() = sp.getBoolean("charging_only", false)
        set(v) = sp.edit().putBoolean("charging_only", v).apply()

    /** false = only DCIM (camera); true = every folder with photos/videos. */
    var allFolders: Boolean
        get() = sp.getBoolean("all_folders", false)
        set(v) = sp.edit().putBoolean("all_folders", v).apply()

    /** Only media added at/after this time (epoch seconds) is backed up. Set at pairing. */
    var since: Long
        get() = sp.getLong("since", Long.MAX_VALUE)
        set(v) = sp.edit().putLong("since", v).apply()

    var lastError: String?
        get() = sp.getString("last_error", null)
        set(v) = sp.edit().putString("last_error", v).apply()

    var lastRunAt: Long
        get() = sp.getLong("last_run", 0)
        set(v) = sp.edit().putLong("last_run", v).apply()

    fun clear() = sp.edit().clear().apply()
}
