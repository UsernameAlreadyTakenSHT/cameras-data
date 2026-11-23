package io.github.usernamealreadytakensht.cameras

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * Offline maps of [layer], the base map on screen: the whole country down to the districts, and
 * each region down to the streets. The files are built every month on GitHub and downloaded on
 * demand. Addresses, speed cameras and surveillance cameras need nothing: they come with the app.
 */
@Composable
fun OfflineSheet(regions: List<Region>, layer: MapLayer) {
    val context = LocalContext.current
    val catalog = MapFiles.catalog
    val progress = MapFiles.progress
    var toConfirm by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { MapFiles.refresh() }
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    fun name(key: String): String {
        val area = key.substringAfter('-')
        return regions.firstOrNull { it.code == area }?.name ?: area
    }

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
                stringResource(R.string.area_title, name(progress.key), stringResource(layer.fullName)),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            LinearProgressIndicator(
                progress = { if (progress.total > 0) (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.maps_progress, size(progress.done), size(progress.total)),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { MapFiles.stop() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.stop))
            }
        }

        MapFiles.error?.let { message ->
            Text(
                // Without the list, the cause is nearly always the lack of a connection.
                if (catalog == null) stringResource(R.string.maps_no_connection)
                else stringResource(R.string.error_download, message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        TileCacheRow(::size)

        // Without the list of published maps (no network yet), the maps already downloaded are
        // still listed, so that they can be deleted.
        if (catalog == null && MapFiles.error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        fun shown(key: String) = catalog != null || key in MapFiles.installed

        val countryKey = MapCatalog.key(layer, MapCatalog.COUNTRY)
        if (shown(countryKey)) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(stringResource(R.string.maps_country_title), style = MaterialTheme.typography.titleSmall)
            MapRow(countryKey, stringResource(R.string.maps_country), progress, ::size, { toDelete = it }) { toConfirm = it }
        }

        val regionRows = regions.filter { it.code != MapCatalog.COUNTRY && shown(MapCatalog.key(layer, it.code)) }
        if (regionRows.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(stringResource(R.string.maps_regions_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.maps_regions_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        regionRows.forEach { region ->
            MapRow(MapCatalog.key(layer, region.code), region.name, progress, ::size, { toDelete = it }) { toConfirm = it }
        }
    }

    toDelete?.let { key ->
        val bytes = MapFiles.installedSize(key)
        AlertDialog(
            onDismissRequest = { toDelete = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        toDelete = null
                        MapFiles.delete(key)
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
            title = { Text(stringResource(R.string.area_title, name(key), stringResource(layer.fullName))) },
            text = { Text(stringResource(R.string.maps_delete_question, size(bytes))) },
        )
    }

    toConfirm?.let { key ->
        val file = catalog?.get(key) ?: return@let
        AlertDialog(
            onDismissRequest = { toConfirm = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        toConfirm = null
                        MapFiles.download(key)
                    },
                ) { Text(stringResource(R.string.download)) }
            },
            dismissButton = { TextButton(onClick = { toConfirm = null }) { Text(stringResource(R.string.cancel)) } },
            title = { Text(stringResource(R.string.area_title, name(key), stringResource(layer.fullName))) },
            text = { Text(stringResource(R.string.maps_confirm, size(file.size))) },
        )
    }
}

/** Size of the cache of the map tiles seen online, chosen among [TileCache.choices]. */
@Composable
private fun TileCacheRow(size: (Long) -> String) {
    var open by remember { mutableStateOf(false) }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.tile_cache_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.tile_cache_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            TextButton(onClick = { open = true }) { Text(size(TileCache.limit) + " ▾") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                TileCache.choices.forEach { bytes ->
                    DropdownMenuItem(
                        text = { Text(size(bytes)) },
                        onClick = {
                            open = false
                            TileCache.choose(bytes)
                        },
                    )
                }
            }
        }
    }
}

/** One map file: its size, its state on the phone, and what can be done with it. */
@Composable
private fun MapRow(
    key: String,
    title: String,
    progress: MapFiles.Progress?,
    size: (Long) -> String,
    onDelete: (String) -> Unit,
    onDownload: (String) -> Unit,
) {
    val published = MapFiles.catalog?.get(key)
    val installedDate = MapFiles.installed[key]
    val running = progress?.key == key
    val details = when {
        published == null && installedDate == null -> stringResource(R.string.maps_unavailable)
        installedDate != null && published != null && published.date > installedDate ->
            stringResource(R.string.maps_update_available, size(published.size))
        installedDate != null -> stringResource(R.string.maps_installed, displayDate(installedDate))
        else -> stringResource(R.string.maps_size, size(published!!.size))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val newer = published != null && (installedDate == null || published.date > installedDate)
        if (published != null && newer) {
            TextButton(onClick = { onDownload(key) }, enabled = progress == null) {
                Text(
                    stringResource(
                        when {
                            running -> R.string.in_progress
                            installedDate != null -> R.string.update
                            else -> R.string.download
                        },
                    ),
                )
            }
        }
        if (installedDate != null) {
            TextButton(onClick = { onDelete(key) }, enabled = !running) {
                Text(stringResource(R.string.delete))
            }
        }
    }
}

/** "2026-11-23" as "23/11/2026". */
private fun displayDate(iso: String): String =
    iso.split('-').let { if (it.size == 3) "${it[2]}/${it[1]}/${it[0]}" else iso }
