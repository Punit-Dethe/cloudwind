package dev.cloudwind.app

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import org.junit.Rule
import org.junit.Test

/** Renders the real screens with sample data to PNGs (no phone needed). Run: ./gradlew :app:recordPaparazziDebug */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        // A Pixel 5 screen, taller than the real one so the whole scrolling page is visible in one image.
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 3300),
        theme = "android:Theme.Material.Light.NoActionBar",
    )

    private val server = "https://cloudwind.example.workers.dev"
    private val now = 1_795_800_000L

    private fun home(ui: UiState, dark: Boolean = false) = paparazzi.snapshot {
        CloudwindTheme(dark = dark, dynamic = false) {
            Surface(Modifier.fillMaxSize()) { HomeScreen(ui.copy(nowSecs = now), HomeActions()) }
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
            folders = listOf(FolderRow("DCIM/Camera/", 90, false), FolderRow("Documents/Scans/", 3, true)),
            allFolders = true,
        ),
    )
}
