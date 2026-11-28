package dev.cloudwind.app

import dev.cloudwind.core.Gates
import dev.cloudwind.core.Policy
import dev.cloudwind.core.folderPolicy

import android.content.Context

/** Non-secret preferences. Defaults are conservative: Wi-Fi only, new photos only. */
class Settings(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("cloudwind_settings", Context.MODE_PRIVATE)

    /** Whether direct home Wi-Fi transfers to the PC are enabled (default on). */
    var directEnabled: Boolean
        get() = sp.getBoolean("direct_enabled", true)
        set(v) = sp.edit().putBoolean("direct_enabled", v).apply()

    var wifiOnly: Boolean
        get() = sp.getBoolean("wifi_only", true)
        set(v) = sp.edit().putBoolean("wifi_only", v).apply()

    /** Big-file gate: hold big files until charging or at least [batteryMin]% battery. */
    var batteryGate: Boolean
        get() = sp.getBoolean("battery_gate", true)
        set(v) = sp.edit().putBoolean("battery_gate", v).apply()

    var batteryMin: Int
        get() = sp.getInt("battery_min", 40).coerceIn(Gates.MIN_BATTERY, Gates.MAX_BATTERY)
        set(v) = sp.edit().putInt("battery_min", v.coerceIn(Gates.MIN_BATTERY, Gates.MAX_BATTERY)).apply()

    /** Files at least this big (MB) count as "big" and are subject to the gates; smaller ones always go. */
    var bigMb: Int
        get() = sp.getInt("big_mb", 50)
        set(v) = sp.edit().putInt("big_mb", v.coerceAtLeast(1)).apply()

    /** Grace period (seconds) before backing up newly captured/edited photos in the background. */
    var newPhotoDelaySecs: Int
        get() = sp.getInt("new_photo_delay_secs", SettleWindow.DEFAULT_DELAY_SECS).coerceIn(0, 600)
        set(v) = sp.edit().putInt("new_photo_delay_secs", v.coerceIn(0, 600)).apply()

    /** `wifiOnly` is now "Wi-Fi only for big files". */
    val gates: Gates
        get() = Gates(bigBytes = bigMb.toLong() shl 20, wifiOnlyForBig = wifiOnly, batteryGate = batteryGate, batteryMinPct = batteryMin).normalized()

    // ---- folder policy: camera is cloud-OK, everything else is Home-only unless flipped ----
    private fun folderSet(key: String): Set<String> = sp.getStringSet(key, emptySet()) ?: emptySet()
    private fun norm(p: String) = p.trim().trimEnd('/')

    fun policyFor(relPath: String): Policy = folderPolicy(relPath, folderSet("home_only_folders"), folderSet("cloud_folders"))

    fun setFolderHomeOnly(relPath: String, homeOnly: Boolean) {
        val p = norm(relPath)
        val h = folderSet("home_only_folders").toMutableSet(); val c = folderSet("cloud_folders").toMutableSet()
        if (homeOnly) { h += p; c -= p } else { c += p; h -= p }
        sp.edit().putStringSet("home_only_folders", h).putStringSet("cloud_folders", c).apply()
    }

    /** Where the PC was last found on a home network, to try first next time. */
    var lastLanHost: String?
        get() = sp.getString("last_lan_host", null)
        set(v) = sp.edit().putString("last_lan_host", v).apply()

    /** The last pass found the PC on this network. */
    var pcOnNetwork: Boolean
        get() = sp.getBoolean("pc_on_network", false)
        set(v) = sp.edit().putBoolean("pc_on_network", v).apply()

    /** Files moved by each route in the last pass (for the status line). */
    var lastDirect: Int
        get() = sp.getInt("last_direct", 0)
        set(v) = sp.edit().putInt("last_direct", v).apply()
    var lastCloud: Int
        get() = sp.getInt("last_cloud", 0)
        set(v) = sp.edit().putInt("last_cloud", v).apply()

    /** Human summary of what is held back and why, e.g. "2 waiting for Wi-Fi". Null when nothing is. */
    var waiting: String?
        get() = sp.getString("waiting", null)
        set(v) = sp.edit().putString("waiting", v).apply()

    /** How many files the last pass held back; lets the worker skip foreground mode when nothing can move. */
    var waitingCount: Int
        get() = sp.getInt("waiting_count", 0)
        set(v) = sp.edit().putInt("waiting_count", v).apply()

    /** false = only DCIM (camera); true = every folder with photos/videos (legacy fallback before explicit selection). */
    var allFolders: Boolean
        get() = sp.getBoolean("all_folders", false)
        set(v) { sp.edit().putBoolean("all_folders", v).apply(); resetScan() }

    // ---- explicit per-folder selection + manually added SAF folders ----
    var hasExplicitFolderSelection: Boolean
        get() = sp.getBoolean("explicit_folders", false)
        set(v) = sp.edit().putBoolean("explicit_folders", v).apply()

    var selectedFolders: Set<String>
        get() = folderSet("selected_folders").map { FolderSelection.norm(it) }.filter { it.isNotEmpty() }.toSet()
        set(v) {
            sp.edit()
                .putBoolean("explicit_folders", true)
                .putStringSet("selected_folders", v.map { FolderSelection.norm(it) }.filter { it.isNotEmpty() }.toSet())
                .apply()
            resetScan()
        }

    fun isFolderSelected(relPath: String): Boolean =
        FolderSelection.isSelected(relPath, hasExplicitFolderSelection, selectedFolders, allFolders)

    /** Persisted manually picked SAF folders: map of normalized relPath -> tree URI string. */
    fun safFolders(): Map<String, String> =
        folderSet("saf_folders").mapNotNull { entry ->
            val idx = entry.indexOf('|')
            if (idx <= 0 || idx >= entry.lastIndex) null
            else FolderSelection.norm(entry.substring(0, idx)) to entry.substring(idx + 1)
        }.toMap()

    fun addSafFolder(relPath: String, treeUri: String) {
        val n = FolderSelection.norm(relPath)
        if (n.isEmpty()) return
        val map = safFolders().toMutableMap()
        map[n] = treeUri
        saveSafFolders(map)
    }

    fun removeSafFolder(relPath: String) {
        val n = FolderSelection.norm(relPath)
        val map = safFolders().toMutableMap()
        if (map.remove(n) != null) saveSafFolders(map)
    }

    private fun saveSafFolders(map: Map<String, String>) {
        val encoded = map.entries.map { (k, v) -> "$k|$v" }.toSet()
        sp.edit().putStringSet("saf_folders", encoded).apply()
    }

    // ---- incremental scanning: MediaStore's per-volume change counter ----
    /** Last MediaStore generation scanned on a volume, valid only for the same MediaStore version. */
    fun scanGeneration(volume: String, version: String): Long =
        if (sp.getString("scan_ver_$volume", null) == version) sp.getLong("scan_gen_$volume", 0) else 0

    fun setScanGeneration(volume: String, version: String, gen: Long) =
        sp.edit().putString("scan_ver_$volume", version).putLong("scan_gen_$volume", gen).apply()

    /** Forget scan progress so the next pass looks at everything again (new date range or folders). */
    fun resetScan() {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("scan_gen_") || it.startsWith("scan_ver_") }.forEach { e.remove(it) }
        e.apply()
    }

    /** Only media added at/after this time (epoch seconds) is backed up. Set at pairing. */
    var since: Long
        get() = sp.getLong("since", Long.MAX_VALUE)
        set(v) { sp.edit().putLong("since", v).apply(); resetScan() }

    var lastError: String?
        get() = sp.getString("last_error", null)
        set(v) = sp.edit().putString("last_error", v).apply()

    /** True when the last pass found the cloud inbox too full for something. Not an error: it clears itself. */
    var inboxFull: Boolean
        get() = sp.getBoolean("inbox_full", false)
        set(v) = sp.edit().putBoolean("inbox_full", v).apply()

    /** Largest single file the inbox accepts, as last reported by the server (0 = unknown). */
    var maxObjectBytes: Long
        get() = sp.getLong("max_object", 0)
        set(v) = sp.edit().putLong("max_object", v).apply()

    /** Gentle heads-up that isn't a failure (e.g. phone is low on space for a big video). */
    var notice: String?
        get() = sp.getString("notice", null)
        set(v) = sp.edit().putString("notice", v).apply()

    /** When this phone was paired (epoch seconds); anchors the "backups stopped" warning. */
    var pairedAt: Long
        get() = sp.getLong("paired_at", 0)
        set(v) = sp.edit().putLong("paired_at", v).apply()

    var lastRunAt: Long
        get() = sp.getLong("last_run", 0)
        set(v) = sp.edit().putLong("last_run", v).apply()

    fun clear() = sp.edit().clear().apply()
}
