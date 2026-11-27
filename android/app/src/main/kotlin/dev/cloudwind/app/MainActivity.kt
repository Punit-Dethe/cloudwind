package dev.cloudwind.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CloudwindTheme { Surface(Modifier.fillMaxSize()) { Root(vm) } } }
    }
}

@Composable
fun CloudwindTheme(dark: Boolean = isSystemInDarkTheme(), dynamic: Boolean = true, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> "%.0f MB".format(b / (1024.0 * 1024))
    else -> "%d KB".format(b / 1024)
}

@Composable
fun Root(vm: MainViewModel) {
    val ui by vm.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Keep status fresh while the screen is visible.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { vm.refresh(); delay(4000) }
        }
    }
    if (ui.paired) Home(vm, ui) else Pairing(vm, ui)
}

@Composable
fun Pairing(vm: MainViewModel, ui: UiState) {
    val ctx = LocalContext.current
    PairingScreen(
        ui,
        onScan = {
            val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(ctx, opts).startScan()
                .addOnSuccessListener { code -> code.rawValue?.let(vm::pair) }
                .addOnFailureListener { vm.message("Couldn't open the scanner. You can paste the code instead.") }
        },
        onPair = vm::pair,
    )
}

@Composable
fun PairingScreen(ui: UiState, onScan: () -> Unit, onPair: (String) -> Unit) {
    var pasted by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(32.dp))
        Text("Cloudwind", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Back up your photos even when your PC is off. Photos are encrypted on this phone before they leave it.")
        Text("On your PC run `cloudwind pair` to show a pairing code, then scan it here.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onScan, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Scan QR code") }
        OutlinedTextField(pasted, { pasted = it }, label = { Text("…or paste the pairing code") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        OutlinedButton(onClick = { onPair(pasted) }, enabled = pasted.isNotBlank() && !ui.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (ui.busy) "Connecting…" else "Pair with pasted code")
        }
        ui.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** Everything the home screen can ask the app to do. Defaults are no-ops so screenshots/tests stay short. */
data class HomeActions(
    val allowAccess: () -> Unit = {},
    val openAppSettings: () -> Unit = {},
    val freeUp: () -> Unit = {},
    val backUpNow: () -> Unit = {},
    val backUpOlder: () -> Unit = {},
    val wifiOnly: (Boolean) -> Unit = {},
    val chargingOnly: (Boolean) -> Unit = {},
    val allFolders: (Boolean) -> Unit = {},
    val batterySettings: () -> Unit = {},
    val phoneSteps: () -> Unit = {},
    val unpair: () -> Unit = {},
)

@Composable
fun Home(vm: MainViewModel, ui: UiState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refresh()
        vm.backUpNow()
    }
    // One system confirmation per batch of 500 photos.
    lateinit var deleteLauncher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>
    deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.deleteResult(r.resultCode == Activity.RESULT_OK)?.let { deleteLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }

    HomeScreen(
        ui,
        HomeActions(
            allowAccess = { permLauncher.launch(MediaAccess.requestList(Build.VERSION.SDK_INT)) },
            openAppSettings = { ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) },
            freeUp = { scope.launch { vm.startDelete()?.let { deleteLauncher.launch(IntentSenderRequest.Builder(it).build()) } } },
            backUpNow = vm::backUpNow,
            backUpOlder = vm::backUpOlder,
            wifiOnly = vm::setWifiOnly,
            chargingOnly = vm::setChargingOnly,
            allFolders = vm::setAllFolders,
            batterySettings = { ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
            phoneSteps = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com"))) },
            unpair = vm::unpair,
        ),
    )
}

@Composable
fun HomeScreen(ui: UiState, a: HomeActions) {
    var confirmUnpair by remember { mutableStateOf(false) }
    val c = ui.counts
    val stale = BackupHealth.isStale(c.pending, ui.lastRunAt, ui.pairedAt, ui.nowSecs)
    val lastBackup = if (ui.lastRunAt > 0) "Last backup ${BackupHealth.ago(ui.nowSecs - ui.lastRunAt)}" else "No backup has finished yet"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column {
            Text("Cloudwind", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (ui.nowSecs > 0) Text(lastBackup, style = MaterialTheme.typography.bodySmall)
        }

        when (ui.access) {
            MediaAccess.NONE -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Allow access to your photos and videos so Cloudwind can back them up.")
                    Button(onClick = a.allowAccess) { Text("Allow access") }
                }
            }
            MediaAccess.PARTIAL -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Only some photos are shared", fontWeight = FontWeight.SemiBold)
                    Text("Cloudwind can only see the photos you picked, so new photos will not back up on their own. Allow access to all photos to fix this.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = a.allowAccess) { Text("Allow all photos") }
                        TextButton(onClick = a.openAppSettings) { Text("Open settings") }
                    }
                }
            }
            MediaAccess.FULL -> {}
        }

        if (stale) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Backups seem to have stopped", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${c.pending} item(s) are waiting and nothing has uploaded for over a day. " +
                            (if (!ui.batteryUnrestricted) "Android is probably limiting Cloudwind in the background. " else "") +
                            "Check Wi-Fi, and don't swipe the app away from recent apps.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = a.backUpNow) { Text("Back up now") }
                        if (!ui.batteryUnrestricted) OutlinedButton(onClick = a.batterySettings) { Text("Battery settings") }
                    }
                }
            }
        }

        // The journey, condensed to what's actionable on the phone.
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat(c.pending.toString(), "Waiting")
                Stat(c.uploaded.toString(), "In cloud")
                Stat(c.synced.toString(), "On your PC")
            }
        }

        if (c.synced > 0) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${c.synced} safe to delete", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Verified on your PC. Deleting frees about ${formatBytes(c.syncedBytes)} on this phone.")
                    Button(onClick = a.freeUp) { Text("Free up space") }
                }
            }
        }

        if (c.uploaded > 0) {
            Text("Waiting for your PC to pick up ${c.uploaded} item(s). Turn it on and run Cloudwind.", style = MaterialTheme.typography.bodyMedium)
        }
        if (ui.inboxFull) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Cloud inbox is full", fontWeight = FontWeight.SemiBold)
                    Text("Waiting for your PC to catch up. Smaller photos keep uploading; big videos go up once there is room. Your originals stay safe on this phone.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (c.skipped > 0) {
            val max = if (ui.maxObjectBytes > 0) " (limit ${formatBytes(ui.maxObjectBytes)} per file)" else ""
            Text("${c.skipped} item(s) are too large for the inbox$max and were not uploaded. Copy them to your PC another way.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        ui.notice?.takeIf { ui.access != MediaAccess.PARTIAL }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        ui.lastError?.let { Text("Last problem: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        ui.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Button(onClick = a.backUpNow, modifier = Modifier.fillMaxWidth()) { Text("Back up now") }
        OutlinedButton(onClick = a.backUpOlder, modifier = Modifier.fillMaxWidth()) { Text("Also back up older photos") }

        Text("Upload rules", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Toggle("Wi-Fi only", ui.wifiOnly, a.wifiOnly)
        Toggle("Only while charging", ui.chargingOnly, a.chargingOnly)
        Toggle("Include all folders (not just camera)", ui.allFolders, a.allFolders)

        Text("Background reliability", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (ui.batteryUnrestricted) {
            Text("Battery restrictions are off for Cloudwind. Good.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Some phones (Xiaomi, Oppo, Vivo, Samsung) stop background apps. Exempt Cloudwind from battery saving.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = a.batterySettings) { Text("Battery settings") }
                TextButton(onClick = a.phoneSteps) { Text("Your phone's steps") }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Connected to ${ui.server}", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { confirmUnpair = true }) { Text("Disconnect this phone") }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Disconnect this phone?") },
            text = { Text("Backups stop and this phone forgets the encryption key. Photos already on your PC are not affected. Photos still in the cloud inbox can only be read by a phone or PC paired with the same key.") },
            confirmButton = { TextButton(onClick = { confirmUnpair = false; a.unpair() }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
