package dev.cloudwind.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cloudwind.app.HomeActions
import dev.cloudwind.app.MediaAccess
import dev.cloudwind.app.SettleWindow
import dev.cloudwind.app.UiState

/**
 * Tab 4: Settings — Cloudwind v4 grouped iOS inset lists.
 *
 * Every row carries a 30dp icon tile with consistent 58dp text-aligned hairlines.
 * Start Delay, Large Files, and Or Battery Above each expand into a uniform 54dp control row
 * with fluid spring physics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesTab(
    ui: UiState,
    a: HomeActions,
    onOpenPhotoAccess: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenHomePc: () -> Unit,
    onToast: (String) -> Unit,
) {
    val col = CwTheme.colors
    var delayExpanded by remember { mutableStateOf(false) }
    var largeExpanded by remember { mutableStateOf(false) }
    var batteryExpanded by remember { mutableStateOf(false) }
    var showDelayInfo by remember { mutableStateOf(false) }
    var showThresholdInfo by remember { mutableStateOf(false) }

    val rowHeight = 54.dp
    val dividerIndent = 58.dp
    val springExpand = expandVertically(spring<IntSize>(dampingRatio = 0.82f, stiffness = 360f)) + fadeIn(spring(stiffness = 380f))
    val springCollapse = shrinkVertically(spring<IntSize>(dampingRatio = 0.88f, stiffness = 420f)) + fadeOut(spring(stiffness = 440f))

    val delayChevRot by animateFloatAsState(if (delayExpanded) 90f else 0f, CwMotion.SpringFloat, label = "delayChev")
    val largeChevRot by animateFloatAsState(if (largeExpanded) 90f else 0f, CwMotion.SpringFloat, label = "largeChev")
    val batteryChevRot by animateFloatAsState(if (batteryExpanded) 90f else 0f, CwMotion.SpringFloat, label = "batteryChev")

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            text = "Settings",
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1.0).sp,
            color = col.ink1,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        // ---- Group 1: Direct to PC & Start Delay ----
        InsetCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .clickable {
                        val next = !ui.directEnabled
                        a.directEnabled(next)
                        onToast(if (next) "Direct to PC enabled" else "Using cloud relay only")
                    }
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IosIconTile(col.blue) { WifiTileIcon(Color.White) }
                    Text("Direct to PC", fontSize = 16.5.sp, color = col.ink1)
                }
                AppleSwitch(
                    checked = ui.directEnabled,
                    onCheckedChange = { next ->
                        a.directEnabled(next)
                        onToast(if (next) "Direct to PC enabled" else "Using cloud relay only")
                    },
                )
            }

            Hairline(indent = dividerIndent)

            // Start Delay (collapsed row; tap to expand a uniform 54dp segmented picker row)
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .clickable { delayExpanded = !delayExpanded }
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IosIconTile(col.amberSolid) { ClockTabIcon(Color.White, Modifier.size(16.dp)) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Start Delay", fontSize = 16.5.sp, color = col.ink1)
                            InfoToggleIcon(active = showDelayInfo, onClick = { showDelayInfo = !showDelayInfo })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = SettleWindow.label(ui.newPhotoDelaySecs),
                            fontSize = 16.sp,
                            fontWeight = if (delayExpanded) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (delayExpanded) col.blue else col.ink2,
                        )
                        ChevronIcon(
                            tint = if (delayExpanded) col.blue else col.ink3,
                            modifier = Modifier.size(13.dp).rotate(delayChevRot),
                        )
                    }
                }

                AnimatedVisibility(visible = showDelayInfo, enter = springExpand, exit = springCollapse) {
                    Text(
                        text = if (ui.newPhotoDelaySecs > 0)
                            "Waits ${SettleWindow.label(ui.newPhotoDelaySecs)} before uploading new shots so quick deletes or edits aren't uploaded twice. Back Up Now sends immediately."
                        else
                            "New photos start backing up as soon as your camera finishes saving.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = col.ink2,
                        modifier = Modifier.padding(start = dividerIndent, end = 16.dp, bottom = 12.dp),
                    )
                }

                AnimatedVisibility(visible = delayExpanded, enter = springExpand, exit = springCollapse) {
                    Column(Modifier.fillMaxWidth()) {
                        Hairline(indent = dividerIndent)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(rowHeight)
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            SlidingSegmentedControl(
                                options = listOf(0 to "Off", 30 to "30s", 60 to "1m", 120 to "2m", 300 to "5m", 600 to "10m"),
                                selected = ui.newPhotoDelaySecs,
                                onSelect = { secs ->
                                    a.newPhotoDelaySecs(secs)
                                    onToast(
                                        if (secs == 0) "Start delay turned off"
                                        else "Start delay set to ${SettleWindow.label(secs)}",
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        // ---- Group 2: Cloud Uploads (Wi-Fi Only -> Large Files -> Wait for Charger -> Or Battery Above) ----
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            SectionLabel("Cloud Uploads")
            InsetCard {
                // 1. Wi-Fi Only
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .clickable {
                            val next = !ui.wifiOnly
                            a.wifiOnly(next)
                            onToast(if (next) "Wi-Fi only for large cloud uploads" else "Mobile data allowed for uploads")
                        }
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IosIconTile(col.blue) { WifiTileIcon(Color.White) }
                        Text("Wi-Fi Only", fontSize = 16.5.sp, color = col.ink1)
                    }
                    AppleSwitch(
                        checked = ui.wifiOnly,
                        onCheckedChange = { next ->
                            a.wifiOnly(next)
                            onToast(if (next) "Wi-Fi only for large cloud uploads" else "Mobile data allowed for uploads")
                        },
                    )
                }

                Hairline(indent = dividerIndent)

                // 2. Large Files (collapsed row; tap to expand a uniform 54dp segmented picker row)
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .clickable { largeExpanded = !largeExpanded }
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IosIconTile(col.amberSolid) { FileTileIcon(Color.White) }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Large Files", fontSize = 16.5.sp, color = col.ink1)
                                InfoToggleIcon(active = showThresholdInfo, onClick = { showThresholdInfo = !showThresholdInfo })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Over ${ui.bigMb} MB",
                                fontSize = 16.sp,
                                fontWeight = if (largeExpanded) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (largeExpanded) col.blue else col.ink2,
                            )
                            ChevronIcon(
                                tint = if (largeExpanded) col.blue else col.ink3,
                                modifier = Modifier.size(13.dp).rotate(largeChevRot),
                            )
                        }
                    }

                    AnimatedVisibility(visible = showThresholdInfo, enter = springExpand, exit = springCollapse) {
                        Text(
                            text = "Files over ${ui.bigMb} MB wait for your Wi-Fi and charger rules. Small photos and home network transfers always go.",
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = col.ink2,
                            modifier = Modifier.padding(start = dividerIndent, end = 16.dp, bottom = 12.dp),
                        )
                    }

                    AnimatedVisibility(visible = largeExpanded, enter = springExpand, exit = springCollapse) {
                        Column(Modifier.fillMaxWidth()) {
                            Hairline(indent = dividerIndent)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(rowHeight)
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                SlidingSegmentedControl(
                                    options = listOf(25 to "25", 50 to "50", 100 to "100", 250 to "250", 500 to "500"),
                                    selected = ui.bigMb,
                                    onSelect = { mb ->
                                        a.bigMb(mb)
                                        onToast("Large file threshold set to $mb MB")
                                    },
                                )
                            }
                        }
                    }
                }

                Hairline(indent = dividerIndent)

                // 3. Wait for Charger
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .clickable { a.batteryGate(!ui.batteryGate) }
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IosIconTile(col.greenSolid) { BoltTileIcon(Color.White) }
                        Text("Wait for Charger", fontSize = 16.5.sp, color = col.ink1)
                    }
                    AppleSwitch(checked = ui.batteryGate, onCheckedChange = a.batteryGate)
                }

                // 4. Or Battery Above (collapsed row with icon tile; tap to expand a uniform 54dp slider row)
                AnimatedVisibility(visible = ui.batteryGate, enter = springExpand, exit = springCollapse) {
                    Column(Modifier.fillMaxWidth()) {
                        Hairline(indent = dividerIndent)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(rowHeight)
                                .clickable { batteryExpanded = !batteryExpanded }
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                IosIconTile(col.greenSolid) { BatteryTileIcon(Color.White) }
                                Text("Or Battery Above", fontSize = 16.5.sp, color = col.ink1)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "${ui.batteryMin}%",
                                    fontSize = 16.sp,
                                    fontWeight = if (batteryExpanded) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (batteryExpanded) col.blue else col.ink2,
                                )
                                ChevronIcon(
                                    tint = if (batteryExpanded) col.blue else col.ink3,
                                    modifier = Modifier.size(13.dp).rotate(batteryChevRot),
                                )
                            }
                        }

                        AnimatedVisibility(visible = batteryExpanded, enter = springExpand, exit = springCollapse) {
                            Column(Modifier.fillMaxWidth()) {
                                Hairline(indent = dividerIndent)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(rowHeight)
                                        .padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text("30%", fontSize = 12.sp, color = col.ink3)
                                    val fraction = ((ui.batteryMin - 30f) / 60f).coerceIn(0f, 1f)
                                    Slider(
                                        value = ui.batteryMin.toFloat(),
                                        onValueChange = { a.batteryMin(it.toInt()) },
                                        valueRange = 30f..90f,
                                        steps = 11,
                                        modifier = Modifier.weight(1f),
                                        thumb = {
                                            Box(
                                                Modifier
                                                    .size(24.dp)
                                                    .shadow(4.dp, CircleShape)
                                                    .clip(CircleShape)
                                                    .background(Color.White),
                                            )
                                        },
                                        track = {
                                            Box(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .height(4.dp)
                                                    .clip(CircleShape)
                                                    .background(col.sliderTrack),
                                            ) {
                                                Box(
                                                    Modifier
                                                        .fillMaxWidth(fraction)
                                                        .height(4.dp)
                                                        .clip(CircleShape)
                                                        .background(col.blue),
                                                )
                                            }
                                        },
                                    )
                                    Text("90%", fontSize = 12.sp, color = col.ink3)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- Group 3: Permissions & Home PC ----
        InsetCard {
            val (photoVal, photoBad) = when (ui.access) {
                MediaAccess.FULL -> "All Photos" to false
                MediaAccess.PARTIAL -> "Limited" to false
                MediaAccess.NONE -> "Off" to true
            }
            SettingsNavRow(
                tileColor = col.blue,
                icon = { PhotoTileIcon(Color.White) },
                title = "Photo Access",
                valueText = photoVal,
                isBad = photoBad,
                onClick = onOpenPhotoAccess,
            )

            Hairline(indent = dividerIndent)

            SettingsNavRow(
                tileColor = col.greenSolid,
                icon = { BatteryTileIcon(Color.White) },
                title = "Background Activity",
                valueText = if (ui.batteryUnrestricted) "On" else "Off",
                isBad = !ui.batteryUnrestricted,
                onClick = onOpenBattery,
            )

            Hairline(indent = dividerIndent)

            val pcVal = when {
                !ui.directAvailable -> "Cloud Only"
                ui.pcOnNetwork -> "Online"
                else -> "Offline"
            }
            SettingsNavRow(
                tileColor = col.slateTile,
                icon = { PcTileIcon(Color.White) },
                title = "Home PC",
                valueText = pcVal,
                isBad = false,
                onClick = onOpenHomePc,
            )
        }

        Text(
            text = "Cloudwind 0.1",
            fontSize = 13.sp,
            color = col.ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        )
    }
}

@Composable
private fun InfoToggleIcon(active: Boolean, onClick: () -> Unit) {
    val col = CwTheme.colors
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        InfoCircleIcon(
            tint = if (active) col.blue else col.ink3,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SettingsNavRow(
    tileColor: Color,
    icon: @Composable () -> Unit,
    title: String,
    valueText: String,
    isBad: Boolean,
    onClick: () -> Unit,
) {
    val col = CwTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IosIconTile(tileColor, icon)
            Text(title, fontSize = 16.5.sp, color = col.ink1)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = valueText,
                fontSize = 16.sp,
                color = if (isBad) col.red else col.ink2,
            )
            ChevronIcon(col.ink3)
        }
    }
}
