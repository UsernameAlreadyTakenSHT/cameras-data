package io.github.usernamealreadytakensht.cameras

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Offline downloads of [layer], the base map on screen, by whole area: the country or one region.
 * Addresses, speed cameras and surveillance cameras need none: they come with the app.
 */
@Composable
fun OfflineSheet(offline: OfflineMaps, regions: List<Region>, layer: MapLayer) {
    val context = LocalContext.current
    val progress = offline.progress
    var toConfirm by remember { mutableStateOf<Region?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.offline_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.offline_help, stringResource(layer.fullName)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (progress != null) {
            Text(
                areaTitle(progress.name, progress.layerId),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (progress.total > 0) {
                LinearProgressIndicator(
                    progress = { (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.downloading, progress.done, progress.total),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            OutlinedButton(onClick = { offline.stop() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.stop))
            }
        }

        offline.error?.let { message ->
            Text(
                stringResource(R.string.error_download, message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (offline.areas.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(stringResource(R.string.saved_title), style = MaterialTheme.typography.titleSmall)
            offline.areas.forEach { area ->
                val size = Formatter.formatShortFileSize(context, area.bytes)
                val state = if (area.complete) size else stringResource(R.string.saved_incomplete, size)
                val details = MapLayer.byId(area.layerId)
                    ?.let { stringResource(R.string.saved_item, stringResource(it.fullName), state) }
                    ?: state
                AreaRow(area.name, details) {
                    if (!area.complete) {
                        TextButton(onClick = { offline.resume(area) }, enabled = progress == null) {
                            Text(stringResource(R.string.resume))
                        }
                    }
                    TextButton(onClick = { offline.delete(area) }) {
                        Text(stringResource(R.string.delete))
                    }
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text(
            stringResource(R.string.areas_title, stringResource(layer.fullName)),
            style = MaterialTheme.typography.titleSmall,
        )
        regions.forEach { region ->
            val plan = remember(region, layer) { region.plan(layer) }
            val saved = offline.areas.any { it.regionCode == region.code && it.layerId == layer.id }
            val running = progress != null && progress.name == region.name && progress.layerId == layer.id
            val details = stringResource(
                R.string.area_estimate,
                Formatter.formatShortFileSize(context, plan.bytes),
                stringResource(detailLabel(plan.maxTileZoom)),
            )
            AreaRow(region.name, details) {
                TextButton(onClick = { toConfirm = region }, enabled = progress == null && !saved) {
                    Text(
                        stringResource(
                            when {
                                running -> R.string.in_progress
                                saved -> R.string.saved
                                else -> R.string.download
                            },
                        ),
                    )
                }
            }
        }
    }

    toConfirm?.let { region ->
        val plan = region.plan(layer)
        AlertDialog(
            onDismissRequest = { toConfirm = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        toConfirm = null
                        offline.download(region, layer)
                    },
                ) {
                    Text(stringResource(R.string.download))
                }
            },
            dismissButton = {
                TextButton(onClick = { toConfirm = null }) { Text(stringResource(R.string.cancel)) }
            },
            title = { Text(areaTitle(region.name, layer.id)) },
            text = {
                Text(
                    stringResource(
                        R.string.confirm_download,
                        Formatter.formatShortFileSize(context, plan.bytes),
                        stringResource(detailLabel(plan.maxTileZoom)),
                    ),
                )
            },
        )
    }
}

/** One line of a list: a title, a line of details, and its buttons on the right. */
@Composable
private fun AreaRow(title: String, details: String, buttons: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        buttons()
    }
}

/** "Bretagne — Plan IGN", or the bare name for an area saved by an earlier version. */
@Composable
private fun areaTitle(name: String, layerId: String?): String {
    val layer = MapLayer.byId(layerId) ?: return name
    return stringResource(R.string.area_title, name, stringResource(layer.fullName))
}

/** What can be told apart at the deepest tile zoom of a download. */
private fun detailLabel(maxTileZoom: Int): Int = when {
    maxTileZoom <= 12 -> R.string.detail_towns
    maxTileZoom <= 14 -> R.string.detail_districts
    maxTileZoom <= 16 -> R.string.detail_streets
    else -> R.string.detail_buildings
}
