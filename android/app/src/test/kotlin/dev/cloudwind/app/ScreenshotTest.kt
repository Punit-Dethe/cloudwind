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
    private val noop = {}
    private val noopB: (Boolean) -> Unit = {}

    private fun home(ui: UiState, needAccess: Boolean = false, dark: Boolean = false) = paparazzi.snapshot {
        CloudwindTheme(dark = dark, dynamic = false) {
            Surface(Modifier.fillMaxSize()) {
                HomeScreen(ui, needAccess, noop, noop, noop, noop, noopB, noopB, noopB, noop, noop, noop)
            }
        }
    }

    @Test
    fun pairing() = paparazzi.snapshot {
        CloudwindTheme(dark = false, dynamic = false) { Surface(Modifier.fillMaxSize()) { PairingScreen(UiState(), {}, {}) } }
    }

    @Test
    fun home_catching_up() = home(
        UiState(
            paired = true, server = server,
            counts = Counts(pending = 3, uploaded = 12, synced = 148, syncedBytes = 1_300_000_000L),
        ),
    )

    @Test
    fun home_first_run_needs_access() = home(
        UiState(paired = true, server = server, counts = Counts(pending = 0)),
        needAccess = true,
    )

    @Test
    fun home_inbox_full_and_too_large() = home(
        UiState(
            paired = true, server = server,
            counts = Counts(pending = 9, uploaded = 41, synced = 0, skipped = 2),
            inboxFull = true, maxObjectBytes = 5L shl 30,
            notice = "Not enough free space on this phone to prepare “VID_20261124_201533.mp4” for upload. Free some space and it will continue.",
        ),
    )

    @Test
    fun home_dark() = home(
        UiState(
            paired = true, server = server,
            counts = Counts(pending = 0, uploaded = 5, synced = 312, syncedBytes = 4_800_000_000L),
            lastError = null,
        ),
        dark = true,
    )
}
