package dev.cloudwind.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    // ---- partial photo access (Android 14) ----
    @Test fun full_access_needs_both_images_and_video() {
        assertEquals(MediaAccess.FULL, MediaAccess.from(34, images = true, video = true, selected = false, legacyStorage = false))
        assertEquals(MediaAccess.PARTIAL, MediaAccess.from(34, images = true, video = false, selected = false, legacyStorage = false))
    }

    @Test fun picking_selected_photos_is_partial_not_full() {
        // What Android 14 grants when the user taps "Select photos and videos".
        assertEquals(MediaAccess.PARTIAL, MediaAccess.from(34, images = false, video = false, selected = true, legacyStorage = false))
        assertEquals(MediaAccess.NONE, MediaAccess.from(34, images = false, video = false, selected = false, legacyStorage = false))
    }

    @Test fun older_android_uses_legacy_storage_permission() {
        assertEquals(MediaAccess.FULL, MediaAccess.from(31, images = false, video = false, selected = false, legacyStorage = true))
        assertEquals(MediaAccess.NONE, MediaAccess.from(31, images = true, video = true, selected = true, legacyStorage = false))
    }

    @Test fun android_14_requests_the_selected_photos_permission_too() {
        assertTrue(MediaAccess.requestList(34).contains("android.permission.READ_MEDIA_VISUAL_USER_SELECTED"))
        assertFalse(MediaAccess.requestList(33).contains("android.permission.READ_MEDIA_VISUAL_USER_SELECTED"))
    }

    // ---- "backups have stopped" ----
    private val day = 86400L

    @Test fun nothing_waiting_is_never_stale() = assertFalse(BackupHealth.isStale(0, 0, 100, 100 + 30 * day))

    @Test fun waiting_work_with_a_day_of_silence_is_stale() {
        assertTrue(BackupHealth.isStale(5, lastSuccess = 1000, pairedAt = 0, now = 1000 + day + 1))
        assertFalse(BackupHealth.isStale(5, lastSuccess = 1000, pairedAt = 0, now = 1000 + day - 1))
    }

    @Test fun fresh_pairing_is_not_stale_before_its_first_run() {
        assertFalse(BackupHealth.isStale(5, lastSuccess = 0, pairedAt = 5000, now = 5000 + 3600))
        assertTrue(BackupHealth.isStale(5, lastSuccess = 0, pairedAt = 5000, now = 5000 + day + 1))
    }

    @Test fun ago_formats_naturally() {
        assertEquals("just now", BackupHealth.ago(20))
        assertEquals("5 min ago", BackupHealth.ago(300))
        assertEquals("3 h ago", BackupHealth.ago(3 * 3600))
        assertEquals("1 day ago", BackupHealth.ago(day + 5))
        assertEquals("3 days ago", BackupHealth.ago(3 * day))
    }

    // ---- Android 15 foreground-service budget ----
    private val h = 3600_000L

    @Test fun ledger_tracks_usage_in_a_rolling_24h_window() {
        val l = FgLedger()
        val now = 100 * h
        l.record(now - 30 * h, now - 28 * h) // outside the window: ignored
        l.record(now - 5 * h, now - 3 * h)   // 2 h
        l.record(now - 1 * h, now)           // 1 h
        assertEquals(3 * h, l.usedMs(now))
        assertEquals(5 * h + 30 * 60_000L - 3 * h, l.remainingMs(now))
    }

    @Test fun ledger_runs_out_and_recovers() {
        val l = FgLedger()
        val t0 = 100 * h
        l.record(t0, t0 + 6 * h)
        assertEquals(0L, l.remainingMs(t0 + 6 * h))
        // Usage ages out gradually: at +25 h five of the six hours are still inside the window...
        assertEquals(30 * 60_000L, l.remainingMs(t0 + 25 * h))
        // ...and the whole allowance is back once all of it is older than 24 h.
        assertEquals(5 * h + 30 * 60_000L, l.remainingMs(t0 + 30 * h))
    }

    @Test fun ledger_survives_save_and_load() {
        val l = FgLedger()
        l.record(1000, 5000); l.record(7000, 9000)
        val back = FgLedger.decode(l.encode())
        assertEquals(l.usedMs(10_000), back.usedMs(10_000))
        assertEquals(0L, FgLedger.decode("garbage;1:x;:").usedMs(10_000))
    }

    // ---- "why is this held back" summary ----
    @Test fun waiting_summary_names_each_reason_once_with_counts() {
        assertEquals(null, SyncRunner.summarizeWaits(emptyMap()))
        val text = SyncRunner.summarizeWaits(mapOf(dev.cloudwind.core.Wait.WIFI to 4, dev.cloudwind.core.Wait.HOME_NETWORK to 3))!!
        assertEquals("4 waiting for Wi-Fi · 3 waiting for your home network", text)
        assertEquals("2 waiting a moment (new)", SyncRunner.summarizeWaits(emptyMap(), settling = 2))
        assertEquals(
            "1 waiting a moment (new) · 3 waiting for Wi-Fi",
            SyncRunner.summarizeWaits(mapOf(dev.cloudwind.core.Wait.WIFI to 3), settling = 1),
        )
    }

    // ---- new-photo grace period (snap -> review -> quick delete / edit) ----
    @Test fun settle_window_holds_brand_new_photos_then_releases_them() {
        assertEquals(45L, SettleWindow.remainingSecs(dateModified = 1000, nowSecs = 1015, delaySecs = 60))
        assertEquals(0L, SettleWindow.remainingSecs(dateModified = 1000, nowSecs = 1060, delaySecs = 60))
        assertEquals(0L, SettleWindow.remainingSecs(dateModified = 1000, nowSecs = 1200, delaySecs = 60))
    }

    @Test fun settle_window_bypasses_delay_on_manual_backup_or_bad_timestamps() {
        assertEquals(0L, SettleWindow.remainingSecs(dateModified = 1000, nowSecs = 1005, delaySecs = 60, ignoreDelay = true))
        assertEquals(0L, SettleWindow.remainingSecs(dateModified = 1000, nowSecs = 1005, delaySecs = 0))
        assertEquals(0L, SettleWindow.remainingSecs(dateModified = 0, nowSecs = 1005, delaySecs = 60))
        assertEquals(0L, SettleWindow.remainingSecs(dateModified = 2000, nowSecs = 1005, delaySecs = 60))
    }

    // ---- proactive in-place edit detection ----
    @Test fun edit_detector_distinguishes_edits_from_v3_backfill() {
        // Legacy pre-v4 row (dateModified == 0) with identical size: backfill timestamp, do NOT re-upload.
        assertEquals(ScanAction.KEEP, EditDetector.classify("synced", 5000, 0, 5000, 1700000000))
        // Untouched v4 row: keep.
        assertEquals(ScanAction.KEEP, EditDetector.classify("synced", 5000, 1000, 5000, 1000))
        // Edited during grace window (still pending): refresh metadata & clear stale hash/stage.
        assertEquals(ScanAction.REFRESH_PENDING, EditDetector.classify("pending", 5000, 1000, 5200, 1010))
        assertEquals(ScanAction.REFRESH_PENDING, EditDetector.classify("pending", 5000, 1000, 5000, 1010))
        // Edited in-place after already uploaded or synced: re-queue for backup!
        assertEquals(ScanAction.REQUEUE_EDITED, EditDetector.classify("synced", 5000, 1000, 4800, 1020))
        assertEquals(ScanAction.REQUEUE_EDITED, EditDetector.classify("uploaded", 5000, 1000, 5000, 1020))
    }

    // ---- per-folder selection, auto-discovery naming, and SAF paths ----
    @Test fun folder_selection_supports_explicit_picks_and_legacy_defaults() {
        val wa = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"
        // Fresh default (not explicit, legacyAllFolders = false): DCIM only.
        assertTrue(FolderSelection.isSelected("DCIM/Camera/", explicit = false, selected = emptySet(), legacyAllFolders = false))
        assertFalse(FolderSelection.isSelected(wa, explicit = false, selected = emptySet(), legacyAllFolders = false))
        // Explicit selection: only user-chosen folders (including WhatsApp, Canva, etc.).
        val picked = setOf("DCIM/Camera", FolderSelection.norm(wa), "Pictures/Canva")
        assertTrue(FolderSelection.isSelected("DCIM/Camera/", explicit = true, selected = picked, legacyAllFolders = false))
        assertTrue(FolderSelection.isSelected(wa, explicit = true, selected = picked, legacyAllFolders = false))
        assertTrue(FolderSelection.isSelected("Pictures/Canva/", explicit = true, selected = picked, legacyAllFolders = false))
        assertFalse(FolderSelection.isSelected("Pictures/Screenshots/", explicit = true, selected = picked, legacyAllFolders = false))
    }

    @Test fun saf_tree_doc_ids_and_media_filters_behave_safely() {
        assertEquals("Pictures/Canva", FolderSelection.relativePathFromTreeDocId("primary:Pictures/Canva"))
        assertEquals(
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
            FolderSelection.relativePathFromTreeDocId("primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images"),
        )
        assertEquals("Photos/Trips", FolderSelection.relativePathFromTreeDocId("1A2B-3C4D:Photos/Trips"))
        assertEquals(null, FolderSelection.relativePathFromTreeDocId("primary:"))

        // SAF IDs are strictly negative and deterministic so they never collide with MediaStore IDs.
        val id1 = FolderSelection.safMediaId("content://com.android.externalstorage.documents/tree/primary%3ADoc/1")
        val id2 = FolderSelection.safMediaId("content://com.android.externalstorage.documents/tree/primary%3ADoc/2")
        assertTrue(id1 < 0L && id2 < 0L && id1 != id2)
        assertEquals(id1, FolderSelection.safMediaId("content://com.android.externalstorage.documents/tree/primary%3ADoc/1"))

        assertTrue(FolderSelection.isSupportedMedia("image/jpeg", "IMG_1.jpg"))
        assertTrue(FolderSelection.isSupportedMedia("video/mp4", "VID_1.mp4"))
        assertTrue(FolderSelection.isSupportedMedia(null, "design.png"))
        assertFalse(FolderSelection.isSupportedMedia("application/octet-stream", ".nomedia"))
        assertFalse(FolderSelection.isSupportedMedia("text/plain", "notes.txt"))
    }

    // ---- history day bucketing, video duration formatting, and rolling thumbnail cap ----
    @Test fun history_logic_groups_days_and_prunes_oldest_thumbnails() {
        val now = 1_795_800_000L // UTC midday reference
        assertEquals("Today", HistoryLogic.dayGroup(now - 120, now, zoneOffsetSecs = 0))
        assertEquals("Yesterday", HistoryLogic.dayGroup(now - 86_400L, now, zoneOffsetSecs = 0))
        assertEquals("Earlier This Week", HistoryLogic.dayGroup(now - 3 * 86_400L, now, zoneOffsetSecs = 0))
        assertEquals("Earlier", HistoryLogic.dayGroup(now - 12 * 86_400L, now, zoneOffsetSecs = 0))

        assertEquals(null, HistoryLogic.formatDuration(0L))
        assertEquals("0:24", HistoryLogic.formatDuration(24_000L))
        assertEquals("2:05", HistoryLogic.formatDuration(125_000L))
        assertEquals("1:04:12", HistoryLogic.formatDuration(3_852_000L))

        val thumbs = (1..505).map { "obj_$it.webp" }
        val dropped = HistoryLogic.thumbsToPrune(thumbs, maxKeep = 500)
        assertEquals(listOf("obj_501.webp", "obj_502.webp", "obj_503.webp", "obj_504.webp", "obj_505.webp"), dropped)
        assertEquals(emptyList<String>(), HistoryLogic.thumbsToPrune(thumbs.take(20), maxKeep = 500))
    }

    // ---- PC destination folder defaults, sanitization, and grouping suggestions ----
    @Test fun pc_folder_mapping_produces_clean_one_level_subfolders() {
        assertEquals("Camera", FolderSelection.defaultPcFolder("DCIM/Camera/"))
        assertEquals("Downloads", FolderSelection.defaultPcFolder("Download/"))
        assertEquals("WhatsApp Images", FolderSelection.defaultPcFolder("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images"))
        assertEquals("Canva", FolderSelection.defaultPcFolder("Pictures/Canva"))

        assertEquals("WhatsApp", FolderSelection.sanitizePcFolder("  WhatsApp  "))
        assertEquals("Work 2026", FolderSelection.sanitizePcFolder("../Work:2026?"))
        assertEquals("hidden", FolderSelection.sanitizePcFolder("..hidden..."))
        assertEquals("", FolderSelection.sanitizePcFolder("   /\\..  "))

        val suggestions = FolderSelection.suggestedPcFolders(
            relPath = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video",
            displayName = "WhatsApp Video",
            existingTargets = listOf("Camera", "WhatsApp", "Canva"),
        )
        assertEquals(listOf("WhatsApp", "WhatsApp Video", "Camera", "Canva"), suggestions)
    }
}
