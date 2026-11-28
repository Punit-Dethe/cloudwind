package dev.cloudwind.app

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.cloudwind.app.ui.AppTab
import org.junit.Rule
import org.junit.Test

/** Renders the real screens with sample data to PNGs (no phone needed). Run: ./gradlew :app:recordPaparazziDebug */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        // Standard phone viewport so the floating pill tab bar anchors naturally at the bottom.
        deviceConfig = DeviceConfig.PIXEL_5,
        theme = "android:Theme.Material.Light.NoActionBar",
    )

    private val server = "https://cloudwind.example.workers.dev"
    private val now = 1_795_800_000L

    private fun home(ui: UiState, dark: Boolean = false, tab: AppTab = AppTab.SYNC) = paparazzi.snapshot {
        CloudwindTheme(dark = dark, dynamic = false) {
            Surface(Modifier.fillMaxSize()) { HomeScreen(ui.copy(nowSecs = now), HomeActions(), initialTab = tab) }
        }
    }

    @Test
    fun pairing() = paparazzi.snapshot {
        CloudwindTheme(dark = false, dynamic = false) { Surface(Modifier.fillMaxSize()) { PairingScreen(UiState(), {}, {}) } }
    }

    @Test
    fun home_catching_up() = home(
        UiState(
            paired = true, server = server, batteryUnrestricted = true, lastRunAt = now - 240,
            counts = Counts(pending = 3, uploaded = 12, synced = 148, syncedBytes = 1_300_000_000L),
        ),
    )

    @Test
    fun home_first_run_needs_access() = home(
        UiState(paired = true, server = server, access = MediaAccess.NONE, counts = Counts(pending = 0)),
    )

    @Test
    fun home_partial_access() = home(
        UiState(
            paired = true, server = server, access = MediaAccess.PARTIAL, lastRunAt = now - 600,
            counts = Counts(pending = 0, uploaded = 2, synced = 7, syncedBytes = 40_000_000L),
        ),
    )

    @Test
    fun home_backups_stopped() = home(
        UiState(
            paired = true, server = server, batteryUnrestricted = false, lastRunAt = now - 3 * 86400L - 3600, pairedAt = now - 9 * 86400L,
            counts = Counts(pending = 37, uploaded = 4, synced = 210, syncedBytes = 2_100_000_000L),
        ),
    )

    @Test
    fun home_inbox_full_and_too_large() = home(
        UiState(
            paired = true, server = server, lastRunAt = now - 1800,
            counts = Counts(pending = 9, uploaded = 41, synced = 0, skipped = 2),
            inboxFull = true, maxObjectBytes = 5L shl 30,
            notice = "Not enough free space on this phone to prepare “VID_20261124_201533.mp4” for upload. Free some space and it will continue.",
        ),
    )

    @Test
    fun home_dark() = home(
        UiState(
            paired = true, server = server, batteryUnrestricted = true, lastRunAt = now - 90,
            counts = Counts(pending = 0, uploaded = 5, synced = 312, syncedBytes = 4_800_000_000L),
        ),
        dark = true,
    )

    @Test
    fun home_pc_on_network() = home(
        UiState(
            paired = true, server = server, directAvailable = true, pcOnNetwork = true, batteryUnrestricted = true, lastRunAt = now - 30,
            counts = Counts(pending = 6, uploaded = 9, synced = 421, syncedBytes = 9_400_000_000L),
            lastDirect = 38, lastCloud = 4, bigMb = 100, batteryMin = 50,
            folders = listOf(FolderRow("DCIM/Camera/", 412, false), FolderRow("Download/", 9, true), FolderRow("Pictures/Screenshots/", 24, true)),
            allFolders = true,
        ),
    )

    @Test
    fun home_away_with_held_back_files() = home(
        UiState(
            paired = true, server = server, directAvailable = true, pcOnNetwork = false, batteryUnrestricted = true, lastRunAt = now - 700,
            counts = Counts(pending = 11, uploaded = 3, synced = 88, syncedBytes = 1_100_000_000L),
            waiting = "4 waiting for Wi-Fi · 2 waiting for charge or battery · 3 waiting for your home network",
            lastDirect = 0, lastCloud = 2, wifiOnly = true, batteryGate = true, batteryMin = 40,
            folders = listOf(
                FolderRow("DCIM/Camera/", 90, false, pending = 3, synced = 87, totalBytes = 1_700_000_000L),
                FolderRow("Documents/Scans/", 3, true, pending = 3, totalBytes = 42_000_000L),
            ),
            allFolders = true,
        ),
    )

    @Test
    fun tab_folders() = home(
        UiState(
            paired = true, server = server, directAvailable = true, pcOnNetwork = true, batteryUnrestricted = true,
            folders = listOf(
                FolderRow("DCIM/Camera", 412, false, pending = 6, synced = 406, totalBytes = 8_400_000_000L, displayName = "Camera"),
                FolderRow("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images", 184, true, synced = 184, totalBytes = 320_000_000L, displayName = "WhatsApp Images"),
                FolderRow("Pictures/Canva", 24, true, totalBytes = 68_000_000L, displayName = "Canva"),
            ),
            availableFolders = listOf(
                FolderRow("Pictures/Screenshots", 64, true, totalBytes = 160_000_000L, displayName = "Screenshots", selected = false),
                FolderRow("Pictures/Telegram", 42, true, totalBytes = 95_000_000L, displayName = "Telegram", selected = false),
                FolderRow("Download", 19, true, totalBytes = 140_000_000L, displayName = "Download", selected = false),
            ),
        ),
        tab = AppTab.FOLDERS,
    )

    @Test
    fun tab_history() = home(
        UiState(
            paired = true,
            server = server,
            counts = Counts(pending = 0, uploaded = 3, synced = 19),
            history = buildList {
                // Today (9 items, mix of photos, cloud badge, video duration, screenshot)
                for (i in 0 until 9) {
                    val vid = i == 2 || i == 7
                    val shot = i == 5
                    add(
                        HistoryItem(
                            id = 100L + i,
                            mediaId = 1000L + i,
                            docUri = null,
                            objectId = "obj_t_$i",
                            name = when {
                                vid -> "VID_${1040 + i}.mp4"
                                shot -> "Screenshot_$i.png"
                                else -> "IMG_${4831 - i}.jpg"
                            },
                            relPath = if (shot) "Pictures/Screenshots/" else "DCIM/Camera/",
                            size = if (vid) 48_000_000L else 3_800_000L,
                            isVideo = vid,
                            durationMs = if (vid) 24_000L + i * 7_000L else 0L,
                            route = if (i == 1 || i == 6) "cloud" else "direct",
                            status = if (i == 1 || i == 6) "cloud" else "pc",
                            transferredAt = now - 600L - i * 1800L,
                            verifiedAt = if (i == 1 || i == 6) null else now - 600L - i * 1800L,
                        ),
                    )
                }
                // Yesterday (8 items)
                for (i in 0 until 8) {
                    val vid = i == 4
                    add(
                        HistoryItem(
                            id = 200L + i,
                            mediaId = 2000L + i,
                            docUri = null,
                            objectId = "obj_y_$i",
                            name = if (vid) "VID_${1020 + i}.mp4" else "IMG_${4810 - i}.jpg",
                            relPath = "DCIM/Camera/",
                            size = if (vid) 62_000_000L else 4_100_000L,
                            isVideo = vid,
                            durationMs = if (vid) 51_000L else 0L,
                            route = "direct",
                            status = if (i == 3) "cloud" else "pc",
                            transferredAt = now - 86_400L - i * 2400L,
                            verifiedAt = if (i == 3) null else now - 86_400L - i * 2400L,
                        ),
                    )
                }
            },
        ),
        tab = AppTab.HISTORY,
    )

    @Test
    fun tab_rules_dark() = home(
        UiState(
            paired = true, server = server, directAvailable = true, pcOnNetwork = true, directEnabled = true,
            batteryUnrestricted = true, bigMb = 100, batteryMin = 50,
            newPhotoDelaySecs = 60, wifiOnly = true, batteryGate = true, allFolders = true,
        ),
        dark = true,
        tab = AppTab.SETTINGS,
    )

    @Test
    fun home_pc_on_network_dark() = home(
        UiState(
            paired = true, server = server, directAvailable = true, pcOnNetwork = true, batteryUnrestricted = true, lastRunAt = now - 30,
            counts = Counts(pending = 6, uploaded = 9, synced = 421, syncedBytes = 9_400_000_000L),
            lastDirect = 38, lastCloud = 4, bigMb = 100, batteryMin = 50,
            allFolders = true,
        ),
        dark = true,
        tab = AppTab.SYNC,
    )

    @Test
    fun tab_folders_dark() = home(
        UiState(
            paired = true, server = server, directAvailable = true, pcOnNetwork = true, batteryUnrestricted = true,
            folders = listOf(
                FolderRow("DCIM/Camera", 412, false, pending = 6, synced = 406, totalBytes = 8_400_000_000L, displayName = "Camera"),
                FolderRow("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images", 184, false, synced = 184, totalBytes = 320_000_000L, displayName = "WhatsApp Images"),
                FolderRow("Pictures/Canva", 24, true, totalBytes = 68_000_000L, displayName = "Canva"),
            ),
            availableFolders = listOf(
                FolderRow("Pictures/Screenshots", 64, false, totalBytes = 160_000_000L, displayName = "Screenshots", selected = false),
                FolderRow("Pictures/Telegram", 42, false, totalBytes = 95_000_000L, displayName = "Telegram", selected = false),
                FolderRow("Download", 19, false, totalBytes = 140_000_000L, displayName = "Download", selected = false),
            ),
        ),
        dark = true,
        tab = AppTab.FOLDERS,
    )

    @Test
    fun tab_history_dark() = home(
        UiState(
            paired = true,
            server = server,
            counts = Counts(pending = 0, uploaded = 3, synced = 19),
            history = buildList {
                for (i in 0 until 9) {
                    val vid = i == 2 || i == 7
                    val shot = i == 5
                    add(
                        HistoryItem(
                            id = 100L + i,
                            mediaId = 1000L + i,
                            docUri = null,
                            objectId = "obj_t_$i",
                            name = when {
                                vid -> "VID_${1040 + i}.mp4"
                                shot -> "Screenshot_$i.png"
                                else -> "IMG_${4831 - i}.jpg"
                            },
                            relPath = if (shot) "Pictures/Screenshots/" else "DCIM/Camera/",
                            size = if (vid) 48_000_000L else 3_800_000L,
                            isVideo = vid,
                            durationMs = if (vid) 24_000L + i * 7_000L else 0L,
                            route = if (i == 1 || i == 6) "cloud" else "direct",
                            status = if (i == 1 || i == 6) "cloud" else "pc",
                            transferredAt = now - 600L - i * 1800L,
                            verifiedAt = if (i == 1 || i == 6) null else now - 600L - i * 1800L,
                        ),
                    )
                }
                for (i in 0 until 8) {
                    val vid = i == 4
                    add(
                        HistoryItem(
                            id = 200L + i,
                            mediaId = 2000L + i,
                            docUri = null,
                            objectId = "obj_y_$i",
                            name = if (vid) "VID_${1020 + i}.mp4" else "IMG_${4810 - i}.jpg",
                            relPath = "DCIM/Camera/",
                            size = if (vid) 62_000_000L else 4_100_000L,
                            isVideo = vid,
                            durationMs = if (vid) 51_000L else 0L,
                            route = "direct",
                            status = if (i == 3) "cloud" else "pc",
                            transferredAt = now - 86_400L - i * 2400L,
                            verifiedAt = if (i == 3) null else now - 86_400L - i * 2400L,
                        ),
                    )
                }
            },
        ),
        dark = true,
        tab = AppTab.HISTORY,
    )
}
