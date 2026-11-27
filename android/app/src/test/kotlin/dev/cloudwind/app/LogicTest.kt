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
}
