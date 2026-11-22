package io.github.usernamealreadytakensht.cameras

import android.content.ActivityNotFoundException
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.IOException

private sealed interface PhotoState {
    data object Loading : PhotoState
    data object None : PhotoState
    data class Failed(val message: String) : PhotoState
    data class Shown(val photo: StreetPhoto, val image: Bitmap) : PhotoState
}

/** The street photo taken closest to [point], among the services turned on. */
@Composable
fun PhotoSheet(point: GeoPoint, panoramax: Boolean, mapillaryToken: String?) {
    val context = LocalContext.current
    val state by produceState<PhotoState>(PhotoState.Loading, point, panoramax, mapillaryToken) {
        value = try {
            val photo = StreetPhotos.find(point, panoramax, mapillaryToken)
            if (photo == null) PhotoState.None else PhotoState.Shown(photo, StreetPhotos.image(photo.imageUrl))
        } catch (e: IOException) {
            PhotoState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.photos_title), style = MaterialTheme.typography.titleLarge)
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
                // Source, author and licence: both services require them next to the photo.
                Text(
                    listOf(
                        photo.provider.shortName,
                        photo.date.split('-').let { if (it.size == 3) "${it[2]}/${it[1]}/${it[0]}" else "" },
                        if (photo.author.isNotEmpty()) "© ${photo.author}" else "",
                        photo.license,
                    ).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(photo.pageUrl)))
                        } catch (_: ActivityNotFoundException) {
                            // No browser installed.
                        }
                    },
                ) {
                    Text(stringResource(R.string.photos_open, photo.provider.shortName))
                }
            }
        }
    }
}

/** Asks for the Mapillary access token; an empty answer turns the service off. */
@Composable
fun MapillaryTokenDialog(current: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var token by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onDone(token.trim()) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.photos_mapillary)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.mapillary_token_help))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.mapillary_token)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}
