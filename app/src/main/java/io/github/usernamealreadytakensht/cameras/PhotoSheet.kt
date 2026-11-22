package io.github.usernamealreadytakensht.cameras

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.IOException
import java.util.Locale

private sealed interface PhotoState {
    data object Loading : PhotoState
    data object None : PhotoState
    data class Failed(val message: String) : PhotoState
    data class Shown(val photo: StreetPhoto, val image: Bitmap) : PhotoState
}

/**
 * Panel of the place long-pressed on the map: its coordinates, the closest Panoramax photo when
 * that service is turned on, and links to look at the place in other services.
 */
@Composable
fun PointSheet(point: GeoPoint, panoramax: Boolean) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            String.format(Locale.ROOT, "%.5f, %.5f", point.lat, point.lon),
            style = MaterialTheme.typography.titleLarge,
        )
        if (panoramax) PanoramaxPhoto(point)
        HorizontalDivider(Modifier.padding(top = 4.dp))
        Text(
            stringResource(R.string.see_elsewhere),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExternalMap.entries.forEach { service ->
            TextButton(onClick = { open(context, service.url(point)) }) {
                Text(stringResource(service.label))
            }
        }
    }
}

@Composable
private fun PanoramaxPhoto(point: GeoPoint) {
    val context = LocalContext.current
    val state by produceState<PhotoState>(PhotoState.Loading, point) {
        value = try {
            val photo = StreetPhotos.find(point)
            if (photo == null) PhotoState.None else PhotoState.Shown(photo, StreetPhotos.image(photo.imageUrl))
        } catch (e: IOException) {
            PhotoState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
    when (val current = state) {
        PhotoState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
        PhotoState.None -> Text(
            stringResource(R.string.photos_none, StreetPhotos.RADIUS_M),
            style = MaterialTheme.typography.bodyMedium,
        )
        is PhotoState.Failed -> Text(
            stringResource(R.string.photos_failed, current.message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        is PhotoState.Shown -> {
            val photo = current.photo
            Image(
                bitmap = current.image.asImageBitmap(),
                contentDescription = stringResource(R.string.photos_title),
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
            )
            // Source, author and licence: required next to the photo.
            Text(
                listOf(
                    "Panoramax",
                    photo.date.split('-').let { if (it.size == 3) "${it[2]}/${it[1]}/${it[0]}" else "" },
                    if (photo.author.isNotEmpty()) "© ${photo.author}" else "",
                    photo.license,
                ).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { open(context, photo.pageUrl) }) {
                Text(stringResource(R.string.see_in_panoramax))
            }
        }
    }
}

private fun open(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        // No browser installed.
    }
}
