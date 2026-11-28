package dev.cloudwind.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cloudwind.app.FolderRow
import dev.cloudwind.app.HomeActions
import dev.cloudwind.app.UiState

/**
 * Tab 2: Folders — Cloudwind v4 unified Apple list card.
 *
 * Shows all selected and auto-discovered device folders in one crisp list (tap any folder
 * to open its detail sheet with Back Up & Home Wi-Fi Only switches), plus a clean
 * "+ Add Folder" pill on the Folders title row for picking custom/hidden folders via SAF.
 */
@Composable
fun FoldersTab(
    ui: UiState,
    a: HomeActions,
    onSelectFolder: (FolderRow) -> Unit,
) {
    val col = CwTheme.colors
    val allFolders = ui.folders + ui.availableFolders

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Folders",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.0).sp,
                color = col.ink1,
            )
            TintedBluePill("+ Add Folder", onClick = a.pickFolder)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InsetCard {
                if (allFolders.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("No Folders Found", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = col.ink1)
                        Text(
                            "Tap + Add Folder above to choose any folder on your phone.",
                            fontSize = 13.5.sp,
                            color = col.ink2,
                        )
                    }
                } else {
                    allFolders.forEachIndexed { idx, folder ->
                        if (idx > 0) Hairline(indent = 72.dp)
                        FolderListRow(
                            folder = folder,
                            thumbDirPath = ui.thumbDirPath,
                            thumbFile = ui.folderThumbs[folder.path],
                            onClick = { onSelectFolder(folder) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Folders marked", fontSize = 13.sp, color = col.ink2)
                HouseSmallIcon(col.ink2, Modifier.size(13.dp))
                Text("stay on your home Wi-Fi.", fontSize = 13.sp, color = col.ink2)
            }
        }
    }
}

@Composable
private fun FolderListRow(
    folder: FolderRow,
    thumbDirPath: String,
    thumbFile: String?,
    onClick: () -> Unit,
) {
    val col = CwTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CachedThumbImage(
                thumbDirPath = thumbDirPath,
                thumbFile = thumbFile,
                fallbackArt = thumbArtForFolder(folder.path),
                modifier = Modifier.size(44.dp),
                cornerRadius = 8.dp,
            )
            Text(
                text = folder.displayName,
                fontSize = 17.sp,
                fontWeight = FontWeight.Normal,
                color = if (folder.selected) col.ink1 else col.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (folder.selected && folder.homeOnly) {
                HouseSmallIcon(col.ink2, Modifier.size(13.dp))
            }
            Text(
                text = if (folder.selected) "%,d".format(folder.count) else "Off",
                fontSize = 16.5.sp,
                color = col.ink2,
            )
            ChevronIcon(col.ink3)
        }
    }
}
