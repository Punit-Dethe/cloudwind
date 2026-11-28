package dev.cloudwind.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cloudwind.app.BackupHealth
import dev.cloudwind.app.HomeActions
import dev.cloudwind.app.MediaAccess
import dev.cloudwind.app.UiState
import dev.cloudwind.app.formatBytes

/**
 * Tab 1: Sync — Cloudwind v4 precision 3-arc ring (no grey background track),
 * clean status + CTA, 3-row legend card, and utility action card.
 */
@Composable
fun SyncTab(
    ui: UiState,
    a: HomeActions,
    onOpenFreeUp: () -> Unit,
    onOpenOlder: () -> Unit,
    onOpenHeldBack: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenPhotoAccess: () -> Unit,
) {
    val col = CwTheme.colors
    val counts = ui.counts
    val stale = BackupHealth.isStale(counts.pending, ui.lastRunAt, ui.pairedAt, ui.nowSecs)
    val directActive = ui.directAvailable && ui.directEnabled && ui.pcOnNetwork
    val totalItems = counts.synced + counts.uploaded + counts.pending

    val ringSubtitle = when {
        ui.access == MediaAccess.NONE && totalItems == 0 -> "No access"
        counts.pending > 0 -> "${counts.pending} left"
        else -> "All safe"
    }

    val (statusTitle, isWarn) = when {
        ui.access == MediaAccess.NONE -> "Photo Access Needed" to true
        ui.access == MediaAccess.PARTIAL -> {
            val label = if (totalItems > 0) "Only $totalItems Photos Visible" else "Limited Photo Access"
            label to true
        }
        stale -> "Paused" to true
        directActive -> "Direct to Home PC" to false
        ui.directAvailable && ui.directEnabled -> "Via Cloud · PC Offline" to false
        else -> "Syncing via Cloud" to false
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        // ---- Open-Canvas Precision Ring Hero ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrecisionRing(
                pcCount = counts.synced,
                cloudCount = counts.uploaded,
                waitingCount = counts.pending,
                subtitle = ringSubtitle,
                paused = stale,
                noAccessEmpty = ui.access == MediaAccess.NONE && totalItems == 0,
            )

            Spacer(Modifier.height(28.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val iconTint = if (isWarn) col.amberSolid else col.ink2
                when {
                    ui.access != MediaAccess.FULL -> PhotoTileIcon(iconTint, Modifier.size(18.dp))
                    stale -> PauseSmallIcon(iconTint, Modifier.size(16.dp))
                    directActive -> WifiTileIcon(iconTint, Modifier.size(18.dp))
                    else -> CloudFilledIcon(iconTint, Modifier.size(18.dp))
                }
                Text(
                    text = statusTitle,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.3).sp,
                    color = col.ink1,
                )
            }

            Spacer(Modifier.height(24.dp))

            when {
                ui.access != MediaAccess.FULL -> {
                    PillPrimaryButton("Allow All Photos", onClick = onOpenPhotoAccess)
                }
                stale -> {
                    PillPrimaryButton(
                        text = if (!ui.batteryUnrestricted) "Turn On" else "Back Up Now",
                        onClick = if (!ui.batteryUnrestricted) onOpenBattery else a.backUpNow,
                    )
                }
                counts.pending > 0 -> {
                    PillPrimaryButton("Back Up Now", onClick = a.backUpNow)
                }
                else -> {
                    PillPrimaryButton("All Caught Up", onClick = a.backUpNow, calm = true)
                }
            }
        }

        // ---- Card 1: 3-Row Legend ----
        InsetCard {
            LegendRow("On Your PC", counts.synced, col.ringPc)
            Hairline(indent = 40.dp)
            LegendRow("In Cloud", counts.uploaded, col.ringCloud)
            Hairline(indent = 40.dp)
            LegendRow("Waiting on Phone", counts.pending, col.ringWait)
        }

        // ---- Card 2: Utility Action Rows ----
        InsetCard {
            val hasHeld = ui.waiting != null && counts.pending > 0
            if (hasHeld) {
                ActionListRow(
                    label = "Held Back",
                    value = counts.pending.toString(),
                    showChevron = true,
                    onClick = onOpenHeldBack,
                )
                Hairline()
            }

            val canFree = counts.synced > 0
            ActionListRow(
                label = "Free Up Space",
                value = if (canFree) formatBytes(counts.syncedBytes) else "Done",
                showChevron = canFree,
                enabled = canFree,
                onClick = onOpenFreeUp,
            )
            Hairline()
            ActionListRow(
                label = "Back Up Older Photos",
                value = null,
                showChevron = true,
                onClick = onOpenOlder,
            )
        }

        // ---- Critical Contextual Notices (only when actually active) ----
        val alerts = buildList {
            if (ui.inboxFull) {
                add("Cloud inbox is full. Waiting for your PC to catch up; originals remain safe on this phone." to col.amber)
            }
            if (counts.skipped > 0) {
                val max = if (ui.maxObjectBytes > 0) " (limit ${formatBytes(ui.maxObjectBytes)})" else ""
                add("${counts.skipped} item(s) exceed the cloud inbox size limit$max." to col.red)
            }
            ui.lastError?.let { add(it to col.red) }
            ui.notice?.takeIf { ui.access != MediaAccess.PARTIAL }?.let { add(it to col.ink2) }
            ui.message?.let { add(it to col.blue) }
        }
        if (alerts.isNotEmpty()) {
            InsetCard {
                alerts.forEachIndexed { idx, (msg, tint) ->
                    if (idx > 0) Hairline()
                    Text(
                        text = msg,
                        fontSize = 13.5.sp,
                        lineHeight = 19.sp,
                        color = tint,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendRow(label: String, count: Int, swatch: Color) {
    val col = CwTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(width = 12.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(swatch),
            )
            Text(label, fontSize = 17.sp, fontWeight = FontWeight.Normal, color = col.ink1)
        }
        Text(
            text = "%,d".format(count),
            fontSize = 17.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = col.ink1,
        )
    }
}

@Composable
private fun ActionListRow(
    label: String,
    value: String?,
    showChevron: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val col = CwTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 17.sp,
            fontWeight = FontWeight.Normal,
            color = col.ink1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (value != null) {
                Text(value, fontSize = 16.5.sp, color = col.ink2)
            }
            if (showChevron) {
                ChevronIcon(col.ink3)
            }
        }
    }
}
