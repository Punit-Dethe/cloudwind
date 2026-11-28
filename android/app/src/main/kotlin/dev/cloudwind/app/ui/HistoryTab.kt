package dev.cloudwind.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cloudwind.app.HistoryItem
import dev.cloudwind.app.HistoryLogic
import dev.cloudwind.app.UiState

enum class HistoryFilter(val label: String) {
    ALL("All Items"),
    PC("On PC"),
    CLOUD("In Cloud"),
}

private sealed interface HistoryRowEntry {
    data class DayHeader(val title: String) : HistoryRowEntry
    data class GridRow(val items: List<HistoryItem>, val isFirst: Boolean, val isLast: Boolean) : HistoryRowEntry
}

/**
 * Tab 3: History — 4-column Apple Photos-style square thumbnail grid backed by real
 * 320px WebP preview thumbnails cached locally (surviving phone storage cleanup).
 */
@Composable
fun HistoryTab(
    ui: UiState,
    filter: HistoryFilter,
    listState: LazyListState,
    onSelectItem: (HistoryItem) -> Unit,
) {
    val col = CwTheme.colors
    val nowSecs = if (ui.nowSecs > 0L) ui.nowSecs else System.currentTimeMillis() / 1000L

    val shown = remember(ui.history, filter) {
        when (filter) {
            HistoryFilter.ALL -> ui.history
            HistoryFilter.PC -> ui.history.filter { it.onPc }
            HistoryFilter.CLOUD -> ui.history.filter { it.inCloud }
        }
    }

    val entries = remember(shown, nowSecs) {
        val grouped = shown.groupBy { HistoryLogic.dayGroup(it.transferredAt, nowSecs) }
        buildList {
            for (day in HistoryLogic.GROUP_ORDER) {
                val list = grouped[day].orEmpty()
                if (list.isEmpty()) continue
                add(HistoryRowEntry.DayHeader(day))
                val chunks = list.chunked(4)
                chunks.forEachIndexed { idx, rowItems ->
                    add(HistoryRowEntry.GridRow(rowItems, isFirst = idx == 0, isLast = idx == chunks.lastIndex))
                }
            }
        }
    }

    val subtitle = when (filter) {
        HistoryFilter.ALL -> "%,d item%s".format(shown.size, if (shown.size == 1) "" else "s")
        HistoryFilter.PC -> "%,d on PC".format(shown.size)
        HistoryFilter.CLOUD -> "%,d in cloud".format(shown.size)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 118.dp),
    ) {
        item(key = "history_title") {
            Column(Modifier.padding(horizontal = 4.dp)) {
                Text(
                    text = "History",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1.0).sp,
                    color = col.ink1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 15.sp,
                    color = col.ink2,
                )
            }
        }

        if (shown.isEmpty()) {
            item(key = "history_empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 84.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = "No Items",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = col.ink1,
                    )
                    Text(
                        text = when (filter) {
                            HistoryFilter.CLOUD -> "Nothing is waiting in the cloud."
                            HistoryFilter.PC -> "Photos verified on your PC will appear here."
                            HistoryFilter.ALL -> "Backed-up photos and videos will appear here."
                        },
                        fontSize = 15.sp,
                        color = col.ink2,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            itemsIndexed(
                items = entries,
                key = { idx, entry ->
                    when (entry) {
                        is HistoryRowEntry.DayHeader -> "hdr_${entry.title}"
                        is HistoryRowEntry.GridRow -> "row_${entry.items.first().id}_$idx"
                    }
                },
            ) { _, entry ->
                when (entry) {
                    is HistoryRowEntry.DayHeader -> {
                        Text(
                            text = entry.title,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.4).sp,
                            color = col.ink1,
                            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 24.dp, bottom = 10.dp),
                        )
                    }
                    is HistoryRowEntry.GridRow -> {
                        val topRadius = if (entry.isFirst) 12.dp else 0.dp
                        val bottomRadius = if (entry.isLast) 12.dp else 0.dp
                        val shape = RoundedCornerShape(
                            topStart = topRadius,
                            topEnd = topRadius,
                            bottomStart = bottomRadius,
                            bottomEnd = bottomRadius,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = if (entry.isLast) 0.dp else 2.dp)
                                .clip(shape),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            for (i in 0 until 4) {
                                val item = entry.items.getOrNull(i)
                                if (item != null) {
                                    HistoryGridCell(
                                        item = item,
                                        thumbDirPath = ui.thumbDirPath,
                                        modifier = Modifier.weight(1f),
                                        onClick = { onSelectItem(item) },
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f).aspectRatio(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryGridCell(
    item: HistoryItem,
    thumbDirPath: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val col = CwTheme.colors
    val durText = remember(item.isVideo, item.durationMs) {
        if (!item.isVideo) null else HistoryLogic.formatDuration(item.durationMs) ?: "VID"
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(col.fillSecondary)
            .clickable(onClick = onClick),
    ) {
        CachedThumbImage(
            thumbDirPath = thumbDirPath,
            thumbFile = item.thumbFile,
            fallbackArt = thumbArtForItem(item.name, item.relPath, item.isVideo, item.id),
            modifier = Modifier.fillMaxSize(),
            cornerRadius = 0.dp,
        )

        if (item.inCloud || durText != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(26.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color(0x66000000),
                        ),
                    ),
            )
        }

        if (item.inCloud) {
            CloudFilledIcon(
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 5.dp, bottom = 4.dp)
                    .size(14.dp),
            )
        }

        if (durText != null) {
            Text(
                text = durText,
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    shadow = Shadow(Color(0x88000000), Offset(0f, 1f), 2f),
                ),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = 4.dp),
            )
        }
    }
}
