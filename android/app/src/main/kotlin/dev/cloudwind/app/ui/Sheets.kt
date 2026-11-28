package dev.cloudwind.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cloudwind.app.FolderRow
import dev.cloudwind.app.FolderSelection
import dev.cloudwind.app.HistoryItem
import dev.cloudwind.app.HistoryLogic
import dev.cloudwind.app.HomeActions
import dev.cloudwind.app.MediaAccess
import dev.cloudwind.app.UiState
import dev.cloudwind.app.formatBytes

sealed interface ActiveSheet {
    data object FreeUp : ActiveSheet
    data object OlderPhotos : ActiveSheet
    data object HeldBack : ActiveSheet
    data object Battery : ActiveSheet
    data object PhotoAccess : ActiveSheet
    data object HomePc : ActiveSheet
    data class FolderDetail(val folderPath: String) : ActiveSheet
    data class HistoryItemDetail(val itemId: Long) : ActiveSheet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudwindSheetHost(
    sheet: ActiveSheet?,
    ui: UiState,
    a: HomeActions,
    onDismiss: () -> Unit,
    onToast: (String) -> Unit,
) {
    if (sheet == null) return
    val col = CwTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = col.sheetBg,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 5.dp)
                    .clip(CircleShape)
                    .background(col.ink3),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (sheet) {
                ActiveSheet.FreeUp -> FreeUpSheetContent(ui, onDismiss) {
                    onDismiss()
                    a.freeUp()
                }
                ActiveSheet.OlderPhotos -> OlderPhotosSheetContent {
                    onDismiss()
                    a.backUpOlder()
                    onToast("Older photos queued for backup")
                }
                ActiveSheet.HeldBack -> HeldBackSheetContent(ui, onDismiss)
                ActiveSheet.Battery -> BatterySheetContent(
                    unrestricted = ui.batteryUnrestricted,
                    onDismiss = onDismiss,
                    onOpenSettings = { onDismiss(); a.batterySettings() },
                    onPhoneSteps = { onDismiss(); a.phoneSteps() },
                )
                ActiveSheet.PhotoAccess -> PhotoAccessSheetContent(
                    access = ui.access,
                    visibleCount = ui.counts.synced + ui.counts.uploaded + ui.counts.pending,
                    onDismiss = onDismiss,
                    onAllow = { onDismiss(); a.allowAccess() },
                    onOpenAppSettings = { onDismiss(); a.openAppSettings() },
                )
                ActiveSheet.HomePc -> HomePcSheetContent(
                    ui = ui,
                    onDismiss = onDismiss,
                    onDisconnect = {
                        onDismiss()
                        a.unpair()
                    },
                )
                is ActiveSheet.FolderDetail -> {
                    val folder = (ui.folders + ui.availableFolders).firstOrNull { it.path == sheet.folderPath }
                    if (folder != null) {
                        FolderDetailSheetContent(
                            folder = folder,
                            thumbDirPath = ui.thumbDirPath,
                            thumbFile = ui.folderThumbs[folder.path],
                            onToggleBackup = { enabled ->
                                if (enabled) {
                                    a.addFolder(folder.path)
                                    onToast("Backing up ${folder.displayName}")
                                } else {
                                    a.removeFolder(folder.path)
                                    onToast("Stopped backing up ${folder.displayName}")
                                }
                            },
                            onToggleHomeOnly = { homeOnly ->
                                a.folderHomeOnly(folder.path, homeOnly)
                                onToast(
                                    if (homeOnly) "${folder.displayName}: Home Wi-Fi only"
                                    else "${folder.displayName}: Cloud + Home",
                                )
                            },
                            onDismiss = onDismiss,
                        )
                    } else {
                        onDismiss()
                    }
                }
                is ActiveSheet.HistoryItemDetail -> {
                    val item = ui.history.firstOrNull { it.id == sheet.itemId }
                    if (item != null) {
                        HistoryItemSheetContent(
                            item = item,
                            thumbDirPath = ui.thumbDirPath,
                            nowSecs = if (ui.nowSecs > 0L) ui.nowSecs else System.currentTimeMillis() / 1000L,
                            onRemoveLocal = {
                                onDismiss()
                                a.removeLocalItem(item)
                            },
                            onDismiss = onDismiss,
                        )
                    } else {
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredSheetHeader(
    title: String,
    subtitle: String? = null,
    icon: (@Composable () -> Unit)? = null,
) {
    val col = CwTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.height(14.dp))
        }
        Text(
            text = title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            color = col.ink1,
            textAlign = TextAlign.Center,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                fontSize = 15.sp,
                lineHeight = 20.5.sp,
                color = col.ink2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SheetIconBadge(color: Color, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun FreeUpSheetContent(ui: UiState, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val col = CwTheme.colors
    val c = ui.counts
    val sizeLabel = formatBytes(c.syncedBytes)

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = sizeLabel,
            fontSize = 50.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-1.6).sp,
            color = col.ink1,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "%,d item%s are already safe on your PC.".format(c.synced, if (c.synced == 1) "" else "s"),
            fontSize = 15.sp,
            color = col.ink2,
            textAlign = TextAlign.Center,
        )
    }

    InsetCard(containerColor = col.sheetSurface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(col.trackBar),
            ) {
                Box(Modifier.weight(0.72f).fillMaxHeight().background(col.ink3))
                Box(Modifier.weight(0.28f).fillMaxHeight().background(col.greenSolid))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Moves to phone trash (~30 days)", fontSize = 13.sp, color = col.ink2)
                Text(sizeLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = col.ink1)
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SheetFullButton(text = "Free Up Space", onClick = onConfirm, bg = col.blue)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Text("Not Now", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = col.blue)
        }
    }
}

@Composable
private fun OlderPhotosSheetContent(onConfirm: () -> Unit) {
    val col = CwTheme.colors
    CenteredSheetHeader(
        title = "Back Up Older Photos",
        subtitle = "Photos already on your PC are skipped automatically.",
    )
    InsetCard(containerColor = col.sheetSurface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Newest photos always back up first so today's shots never wait behind older archives.", fontSize = 14.sp, lineHeight = 19.sp, color = col.ink2)
            Text("Large files still respect your Wi-Fi and charger settings.", fontSize = 14.sp, lineHeight = 19.sp, color = col.ink2)
        }
    }
    SheetFullButton(text = "Back Up Older Photos", onClick = onConfirm, bg = col.blue)
}

@Composable
private fun HeldBackSheetContent(ui: UiState, onDismiss: () -> Unit) {
    val col = CwTheme.colors
    val parts = ui.waiting?.split(" · ").orEmpty()
    CenteredSheetHeader(
        title = "Held Back",
        subtitle = "These upload automatically when conditions are met.",
    )
    InsetCard(containerColor = col.sheetSurface) {
        parts.forEachIndexed { idx, line ->
            if (idx > 0) Hairline()
            val firstSpace = line.indexOf(' ')
            val count = if (firstSpace > 0) line.substring(0, firstSpace) else ""
            val label = if (firstSpace > 0) {
                line.substring(firstSpace + 1).replaceFirstChar { it.uppercase() }
            } else line
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, fontSize = 16.sp, color = col.ink1, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (count.isNotEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    Text(count, fontSize = 16.sp, color = col.ink2)
                }
            }
        }
    }
    SheetFullButton(text = "Done", onClick = onDismiss, bg = col.fillSecondary, fg = col.ink1)
}

@Composable
private fun BatterySheetContent(
    unrestricted: Boolean,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onPhoneSteps: () -> Unit,
) {
    val col = CwTheme.colors
    CenteredSheetHeader(
        title = "Background Activity",
        subtitle = if (unrestricted) "Cloudwind can back up while your screen is off."
        else "Allow Cloudwind to back up quietly while your screen is off.",
        icon = { SheetIconBadge(col.greenSolid) { BatteryTileIcon(Color.White, Modifier.size(30.dp)) } },
    )
    if (unrestricted) {
        SheetFullButton(text = "Done", onClick = onDismiss, bg = col.fillSecondary, fg = col.ink1)
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetFullButton(text = "Open Settings", onClick = onOpenSettings, bg = col.blue)
            SheetFullButton(text = "Device-Specific Steps", onClick = onPhoneSteps, bg = col.fillSecondary, fg = col.ink1)
        }
    }
}

@Composable
private fun PhotoAccessSheetContent(
    access: MediaAccess,
    visibleCount: Int,
    onDismiss: () -> Unit,
    onAllow: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val col = CwTheme.colors
    val full = access == MediaAccess.FULL
    CenteredSheetHeader(
        title = "Photo Access",
        subtitle = when (access) {
            MediaAccess.FULL -> "Cloudwind can see your whole photo library."
            MediaAccess.PARTIAL -> "Cloudwind can only see $visibleCount selected photos. New shots won't back up automatically."
            MediaAccess.NONE -> "Allow access to photos and videos so Cloudwind can back them up automatically."
        },
        icon = { SheetIconBadge(col.blue) { PhotoTileIcon(Color.White, Modifier.size(30.dp)) } },
    )
    if (full) {
        SheetFullButton(text = "Done", onClick = onDismiss, bg = col.fillSecondary, fg = col.ink1)
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetFullButton(text = "Allow All Photos", onClick = onAllow, bg = col.blue)
            SheetFullButton(text = "Open App Settings", onClick = onOpenAppSettings, bg = col.fillSecondary, fg = col.ink1)
        }
    }
}

@Composable
private fun HomePcSheetContent(
    ui: UiState,
    onDismiss: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val col = CwTheme.colors
    var confirmDisconnect by remember { mutableStateOf(false) }
    val statusSub = when {
        !ui.directAvailable -> "Cloud Relay Only"
        ui.pcOnNetwork -> "Online on Home Network"
        else -> "Offline · Using Encrypted Cloud Relay"
    }

    CenteredSheetHeader(
        title = "Home PC",
        subtitle = statusSub,
        icon = { SheetIconBadge(col.slateTile) { PcTileIcon(Color.White, Modifier.size(30.dp)) } },
    )

    InsetCard(containerColor = col.sheetSurface) {
        SheetKvRow("Inbox", ui.server.removePrefix("https://").removePrefix("http://"))
        Hairline()
        SheetKvRow("Direct Sync", if (ui.directAvailable && ui.directEnabled) "Enabled" else "Off")
    }

    if (confirmDisconnect) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Backups will stop and this phone will forget its encryption key. Photos already on your PC are untouched.",
                fontSize = 13.5.sp,
                lineHeight = 18.5.sp,
                color = col.ink2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            SheetFullButton(text = "Confirm Disconnect", onClick = onDisconnect, bg = col.redTint, fg = col.red)
            SheetFullButton(text = "Cancel", onClick = { confirmDisconnect = false }, bg = col.fillSecondary, fg = col.ink1)
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetFullButton(
                text = "Disconnect This Phone",
                onClick = { confirmDisconnect = true },
                bg = col.sheetSurface,
                fg = col.red,
            )
            SheetFullButton(text = "Done", onClick = onDismiss, bg = col.fillSecondary, fg = col.ink1)
        }
    }
}

@Composable
private fun FolderDetailSheetContent(
    folder: FolderRow,
    thumbDirPath: String,
    thumbFile: String?,
    onToggleBackup: (Boolean) -> Unit,
    onToggleHomeOnly: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val col = CwTheme.colors
    val parent = FolderSelection.subtitlePath(folder.path)
    val sub = buildString {
        if (parent.isNotEmpty()) append("$parent · ")
        append("%,d item%s".format(folder.count, if (folder.count == 1) "" else "s"))
        if (folder.totalBytes > 0L) append(" · ${formatBytes(folder.totalBytes)}")
    }

    CenteredSheetHeader(
        title = folder.displayName,
        subtitle = sub,
        icon = {
            CachedThumbImage(
                thumbDirPath = thumbDirPath,
                thumbFile = thumbFile,
                fallbackArt = thumbArtForFolder(folder.path),
                modifier = Modifier.size(72.dp),
                cornerRadius = 16.dp,
            )
        },
    )

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        InsetCard(containerColor = col.sheetSurface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clickable { onToggleBackup(!folder.selected) }
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Back Up", fontSize = 16.5.sp, color = col.ink1)
                AppleSwitch(checked = folder.selected, onCheckedChange = onToggleBackup)
            }
            Hairline()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clickable { onToggleHomeOnly(!folder.homeOnly) }
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Home Wi-Fi Only", fontSize = 16.5.sp, color = col.ink1)
                AppleSwitch(checked = folder.homeOnly, onCheckedChange = onToggleHomeOnly)
            }
        }
        SectionFootnote("Never uploaded to the cloud. Goes straight to your PC at home.")
    }

    SheetFullButton(text = "Done", onClick = onDismiss, bg = col.fillSecondary, fg = col.ink1)
}

@Composable
private fun HistoryItemSheetContent(
    item: HistoryItem,
    thumbDirPath: String,
    nowSecs: Long,
    onRemoveLocal: () -> Unit,
    onDismiss: () -> Unit,
) {
    val col = CwTheme.colors
    val dateLabel = HistoryLogic.formatDateTime(item.transferredAt, nowSecs)

    CachedThumbImage(
        thumbDirPath = thumbDirPath,
        thumbFile = item.thumbFile,
        fallbackArt = thumbArtForItem(item.name, item.relPath, item.isVideo, item.id),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f),
        cornerRadius = 16.dp,
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = item.name,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
            color = col.ink1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "$dateLabel · ${formatBytes(item.size)}",
            fontSize = 15.sp,
            color = col.ink2,
            textAlign = TextAlign.Center,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 2.dp),
        ) {
            if (item.onPc) {
                CheckSmallIcon(col.ink2, Modifier.size(15.dp))
                Text(
                    text = if (item.localRemoved) "Saved on your PC · Removed from phone" else "Saved on your PC",
                    fontSize = 15.sp,
                    color = col.ink2,
                )
            } else {
                CloudFilledIcon(col.ink2, Modifier.size(15.dp))
                Text(
                    text = "In the cloud, on its way to your PC",
                    fontSize = 15.sp,
                    color = col.ink2,
                )
            }
        }
    }

    if (item.onPc && !item.localRemoved && item.docUri == null) {
        SheetFullButton(
            text = "Remove from Phone",
            onClick = onRemoveLocal,
            bg = col.sheetSurface,
            fg = col.red,
        )
    } else {
        SheetFullButton(
            text = "Done",
            onClick = onDismiss,
            bg = col.fillSecondary,
            fg = col.ink1,
        )
    }
}

@Composable
private fun SheetKvRow(key: String, value: String) {
    val col = CwTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, fontSize = 16.sp, color = col.ink1)
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            fontSize = 15.5.sp,
            color = col.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
