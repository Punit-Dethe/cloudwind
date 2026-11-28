package dev.cloudwind.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.cloudwind.app.ui.ActiveSheet
import dev.cloudwind.app.ui.AppTab
import dev.cloudwind.app.ui.BrandGlyphIcon
import dev.cloudwind.app.ui.CheckSmallIcon
import dev.cloudwind.app.ui.CloudwindDesignTheme
import dev.cloudwind.app.ui.CloudwindSheetHost
import dev.cloudwind.app.ui.CwMotion
import dev.cloudwind.app.ui.CwTheme
import dev.cloudwind.app.ui.FilterCircleIcon
import dev.cloudwind.app.ui.FloatingPillTabBar
import dev.cloudwind.app.ui.FoldersTab
import dev.cloudwind.app.ui.HistoryFilter
import dev.cloudwind.app.ui.HistoryTab
import dev.cloudwind.app.ui.InsetCard
import dev.cloudwind.app.ui.PillPrimaryButton
import dev.cloudwind.app.ui.PillSecondaryButton
import dev.cloudwind.app.ui.RulesTab
import dev.cloudwind.app.ui.SyncTab
import dev.cloudwind.app.ui.ThemeToggleIcon
import dev.cloudwind.app.ui.springClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Keep the native launch splash up for the ~20-40ms initial DB/pairing check so
        // the Pairing screen never flashes before Home appears.
        val launchStart = SystemClock.elapsedRealtime()
        val contentView = findViewById<android.view.View>(android.R.id.content)
        contentView.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val ready = vm.state.value.loaded || (SystemClock.elapsedRealtime() - launchStart) > 1500L
                    if (ready) {
                        contentView.viewTreeObserver.removeOnPreDrawListener(this)
                    }
                    return ready
                }
            },
        )
        setContent {
            val sysDark = isSystemInDarkTheme()
            var darkOverride by remember { mutableStateOf<Boolean?>(null) }
            val isDark = darkOverride ?: sysDark

            LaunchedEffect(isDark) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                    navigationBarStyle = if (isDark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                )
            }

            CloudwindTheme(dark = isDark, dynamic = false) {
                Surface(Modifier.fillMaxSize(), color = CwTheme.colors.bgApp) {
                    Root(vm, onToggleTheme = { darkOverride = !isDark })
                }
            }
        }
    }
}

@Composable
fun CloudwindTheme(
    dark: Boolean = isSystemInDarkTheme(),
    @Suppress("UNUSED_PARAMETER") dynamic: Boolean = false,
    content: @Composable () -> Unit,
) {
    CloudwindDesignTheme(dark = dark, content = content)
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> "%.1f MB".format(b / (1024.0 * 1024))
    else -> "%d KB".format((b / 1024).coerceAtLeast(1))
}

@Composable
fun Root(vm: MainViewModel, onToggleTheme: () -> Unit = {}) {
    val ui by vm.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Keep status fresh while the screen is visible.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refresh()
                delay(4000)
            }
        }
    }
    when {
        !ui.loaded -> Box(Modifier.fillMaxSize().background(CwTheme.colors.bgApp))
        ui.paired -> Home(vm, ui, onToggleTheme)
        else -> Pairing(vm, ui, onToggleTheme)
    }
}

@Composable
fun Pairing(vm: MainViewModel, ui: UiState, onToggleTheme: () -> Unit = {}) {
    val ctx = LocalContext.current
    PairingScreen(
        ui = ui,
        onScan = {
            val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(ctx, opts).startScan()
                .addOnSuccessListener { code -> code.rawValue?.let(vm::pair) }
                .addOnFailureListener { vm.message("Couldn't open the scanner. You can paste the code instead.") }
        },
        onPair = vm::pair,
        onToggleTheme = onToggleTheme,
    )
}

@Composable
fun PairingScreen(
    ui: UiState,
    onScan: () -> Unit,
    onPair: (String) -> Unit,
    onToggleTheme: () -> Unit = {},
) {
    val col = CwTheme.colors
    var pasted by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(col.bgApp)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrandGlyphIcon(col.blue)
                Text("Cloudwind", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, color = col.ink1)
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(col.fillSecondary)
                    .springClickable(onClick = onToggleTheme),
                contentAlignment = Alignment.Center,
            ) {
                ThemeToggleIcon(col.isDark, col.ink1)
            }
        }

        Spacer(Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Private photo backup\nto your own PC.",
                fontSize = 30.sp,
                lineHeight = 35.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.9).sp,
                color = col.ink1,
            )
            Text(
                text = "Photos go straight across your home Wi-Fi when you're home, or wait in your encrypted cloud inbox when your PC is off.",
                fontSize = 15.sp,
                lineHeight = 21.sp,
                color = col.ink2,
            )
        }

        InsetCard {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Pair with your PC", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = col.ink1)
                Text(
                    "Open Cloudwind on your PC and click Pair Phone (or run `cloudwind pair`), then scan the QR code.",
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    color = col.ink2,
                )
                PillPrimaryButton(
                    text = if (ui.busy) "Connecting…" else "Scan QR Code",
                    onClick = onScan,
                    enabled = !ui.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        InsetCard {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Or paste pairing code", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = col.ink1)
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    placeholder = { Text("cloudwind://pair/…", color = col.ink3, fontSize = 13.5.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = col.blue,
                        unfocusedBorderColor = col.hairline,
                        focusedTextColor = col.ink1,
                        unfocusedTextColor = col.ink1,
                    ),
                )
                PillSecondaryButton(
                    text = if (ui.busy) "Connecting…" else "Pair with Code",
                    onClick = { if (pasted.isNotBlank() && !ui.busy) onPair(pasted) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        ui.message?.let {
            Text(it, fontSize = 13.5.sp, color = col.red, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

/** Everything the home screen can ask the app to do. Defaults are no-ops so screenshots/tests stay short. */
data class HomeActions(
    val allowAccess: () -> Unit = {},
    val openAppSettings: () -> Unit = {},
    val freeUp: () -> Unit = {},
    val removeLocalItem: (HistoryItem) -> Unit = {},
    val backUpNow: () -> Unit = {},
    val backUpOlder: () -> Unit = {},
    val directEnabled: (Boolean) -> Unit = {},
    val wifiOnly: (Boolean) -> Unit = {},
    val batteryGate: (Boolean) -> Unit = {},
    val batteryMin: (Int) -> Unit = {},
    val bigMb: (Int) -> Unit = {},
    val newPhotoDelaySecs: (Int) -> Unit = {},
    val folderHomeOnly: (String, Boolean) -> Unit = { _, _ -> },
    val folderPcDest: (String, String?) -> Unit = { _, _ -> },
    val addFolder: (String) -> Unit = {},
    val removeFolder: (String) -> Unit = {},
    val pickFolder: () -> Unit = {},
    val allFolders: (Boolean) -> Unit = {},
    val batterySettings: () -> Unit = {},
    val phoneSteps: () -> Unit = {},
    val unpair: () -> Unit = {},
)

@Composable
fun Home(vm: MainViewModel, ui: UiState, onToggleTheme: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refresh()
        vm.backUpNow()
    }
    // Ask for photo access as soon as the home screen first appears, instead of waiting for a tap.
    LaunchedEffect(Unit) {
        if (MediaAccess.current(ctx) == MediaAccess.NONE) permLauncher.launch(MediaAccess.requestList(Build.VERSION.SDK_INT))
    }
    // One system confirmation per batch of 500 photos (or single item from History).
    lateinit var deleteLauncher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>
    deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.deleteResult(r.resultCode == Activity.RESULT_OK)?.let { deleteLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }
    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addSafTreeUri(uri)
    }

    HomeScreen(
        ui = ui,
        a = HomeActions(
            allowAccess = { permLauncher.launch(MediaAccess.requestList(Build.VERSION.SDK_INT)) },
            openAppSettings = { ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) },
            freeUp = { scope.launch { vm.startDelete()?.let { deleteLauncher.launch(IntentSenderRequest.Builder(it).build()) } } },
            removeLocalItem = { item -> scope.launch { vm.startDeleteSingle(item)?.let { deleteLauncher.launch(IntentSenderRequest.Builder(it).build()) } } },
            backUpNow = vm::backUpNow,
            backUpOlder = vm::backUpOlder,
            directEnabled = vm::setDirectEnabled,
            wifiOnly = vm::setWifiOnly,
            batteryGate = vm::setBatteryGate,
            batteryMin = vm::setBatteryMin,
            bigMb = vm::setBigMb,
            newPhotoDelaySecs = vm::setNewPhotoDelaySecs,
            folderHomeOnly = vm::setFolderHomeOnly,
            folderPcDest = vm::setFolderPcDest,
            addFolder = vm::addFolder,
            removeFolder = vm::removeFolder,
            pickFolder = { folderPickerLauncher.launch(null) },
            allFolders = vm::setAllFolders,
            batterySettings = { ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
            phoneSteps = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com"))) },
            unpair = vm::unpair,
        ),
        onToggleTheme = onToggleTheme,
    )
}

@Composable
fun HomeScreen(
    ui: UiState,
    a: HomeActions,
    onToggleTheme: () -> Unit = {},
    initialTab: AppTab = AppTab.SYNC,
) {
    val col = CwTheme.colors
    var activeTab by remember { mutableStateOf(initialTab) }
    var activeSheet by remember { mutableStateOf<ActiveSheet?>(null) }
    var historyFilter by remember { mutableStateOf(HistoryFilter.ALL) }
    var filterMenuOpen by remember { mutableStateOf(false) }
    var toastText by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()
    val historyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun showToast(msg: String) {
        toastText = msg
        scope.launch {
            delay(2300)
            if (toastText == msg) toastText = null
        }
    }

    val isSyncTab = activeTab == AppTab.SYNC
    val isHistoryTab = activeTab == AppTab.HISTORY
    val scrolled = if (isHistoryTab) {
        historyListState.firstVisibleItemIndex > 0 || historyListState.firstVisibleItemScrollOffset > 16
    } else {
        scrollState.value > 16
    }
    val showCenterTitle = !isSyncTab && if (isHistoryTab) {
        historyListState.firstVisibleItemIndex > 0 || historyListState.firstVisibleItemScrollOffset > 48
    } else {
        scrollState.value > 48
    }
    val centerAlpha by animateFloatAsState(
        targetValue = if (showCenterTitle) 1f else 0f,
        animationSpec = CwMotion.SpringFloat,
        label = "hdrTitle",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(col.bgApp),
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---- Contextual Sticky Top Bar ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (scrolled) col.bgAppFrosted else col.bgApp)
                    .statusBarsPadding(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 16.dp),
                ) {
                    if (isSyncTab) {
                        Row(
                            modifier = Modifier.align(Alignment.CenterStart),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            BrandGlyphIcon(col.blue)
                            Text(
                                text = "Cloudwind",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.5).sp,
                                color = col.ink1,
                            )
                        }
                    }

                    Text(
                        text = activeTab.label,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.4).sp,
                        color = col.ink1,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .alpha(centerAlpha),
                    )

                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (isHistoryTab) {
                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .springClickable { filterMenuOpen = true },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    FilterCircleIcon(col.blue)
                                }
                                DropdownMenu(
                                    expanded = filterMenuOpen,
                                    onDismissRequest = { filterMenuOpen = false },
                                    containerColor = col.menuBg,
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    HistoryFilter.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                ) {
                                                    Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) {
                                                        if (option == historyFilter) {
                                                            CheckSmallIcon(col.ink1, Modifier.size(14.dp))
                                                        }
                                                    }
                                                    Text(option.label, fontSize = 16.sp, color = col.ink1)
                                                }
                                            },
                                            onClick = {
                                                historyFilter = option
                                                filterMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(col.fillSecondary)
                                .springClickable(onClick = onToggleTheme),
                            contentAlignment = Alignment.Center,
                        ) {
                            ThemeToggleIcon(col.isDark, col.ink1)
                        }
                    }
                }
                if (scrolled) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(col.hairline),
                    )
                }
            }

            // ---- Smooth Fluid Tab Content ----
            AnimatedContent(
                targetState = activeTab,
                transitionSpec = {
                    fadeIn(tween(220)) togetherWith fadeOut(tween(150))
                },
                label = "tabSwitch",
                modifier = Modifier.weight(1f),
            ) { tab ->
                if (tab == AppTab.HISTORY) {
                    HistoryTab(
                        ui = ui,
                        filter = historyFilter,
                        listState = historyListState,
                        onSelectItem = { item -> activeSheet = ActiveSheet.HistoryItemDetail(item.id) },
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 120.dp)
                            .navigationBarsPadding(),
                    ) {
                        when (tab) {
                            AppTab.SYNC -> SyncTab(
                                ui = ui,
                                a = a,
                                onOpenFreeUp = { activeSheet = ActiveSheet.FreeUp },
                                onOpenOlder = { activeSheet = ActiveSheet.OlderPhotos },
                                onOpenHeldBack = { activeSheet = ActiveSheet.HeldBack },
                                onOpenBattery = { activeSheet = ActiveSheet.Battery },
                                onOpenPhotoAccess = { activeSheet = ActiveSheet.PhotoAccess },
                            )
                            AppTab.FOLDERS -> FoldersTab(
                                ui = ui,
                                a = a,
                                onSelectFolder = { folder -> activeSheet = ActiveSheet.FolderDetail(folder.path) },
                            )
                            AppTab.SETTINGS -> RulesTab(
                                ui = ui,
                                a = a,
                                onOpenPhotoAccess = { activeSheet = ActiveSheet.PhotoAccess },
                                onOpenBattery = { activeSheet = ActiveSheet.Battery },
                                onOpenHomePc = { activeSheet = ActiveSheet.HomePc },
                                onToast = ::showToast,
                            )
                            AppTab.HISTORY -> Unit
                        }
                    }
                }
            }
        }

        // ---- Subtle Bottom Gradient Scrim ----
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(112.dp)
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.68f to col.bgApp,
                        1.0f to col.bgApp,
                    ),
                ),
        )

        // ---- Floating Frosted-Glass 4-Tab Pill Bar ----
        FloatingPillTabBar(
            active = activeTab,
            onSelect = { next ->
                if (next == activeTab) {
                    scope.launch {
                        if (next == AppTab.HISTORY) historyListState.animateScrollToItem(0)
                        else scrollState.animateScrollTo(0)
                    }
                } else {
                    activeTab = next
                    filterMenuOpen = false
                    scope.launch { scrollState.scrollTo(0) }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        )

        // ---- Quiet Floating Top Toast (Spring Pop) ----
        AnimatedVisibility(
            visible = toastText != null,
            enter = fadeIn(tween(180)) + slideInVertically(spring(dampingRatio = 0.72f, stiffness = 460f)) { -it },
            exit = fadeOut(tween(160)) + slideOutVertically(spring(stiffness = 500f)) { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 54.dp, start = 20.dp, end = 20.dp),
        ) {
            Row(
                modifier = Modifier
                    .shadow(12.dp, CircleShape)
                    .clip(CircleShape)
                    .background(col.toastBg)
                    .padding(horizontal = 15.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(col.greenSolid),
                    contentAlignment = Alignment.Center,
                ) {
                    CheckSmallIcon(Color.White, Modifier.size(11.dp))
                }
                Text(
                    text = toastText.orEmpty(),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = col.toastInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // ---- Bottom Sheets Host ----
        CloudwindSheetHost(
            sheet = activeSheet,
            ui = ui,
            a = a,
            onDismiss = { activeSheet = null },
            onToast = ::showToast,
        )
    }
}
