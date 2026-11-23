package io.github.usernamealreadytakensht.cameras

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.delay
import org.json.JSONException

/**
 * @param offline the service could not be reached: [results] come from the bundled addresses.
 */
private data class SearchState(
    val results: List<SearchResult> = emptyList(),
    val loading: Boolean = false,
    val offline: Boolean = false,
)

/** Pause after the last keystroke before the service is asked. */
private const val TYPING_PAUSE_MS = 350L

/**
 * Search field with its results underneath. A position typed by hand (coordinates, geohash) is
 * recognised on the spot; anything else goes to the IGN geocoder, or to the addresses bundled in
 * the app when there is no connection.
 */
@Composable
fun SearchBar(
    addresses: AddressStore,
    onResult: (SearchResult) -> Unit,
    onCleared: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // Results panel shown: closed once a result is picked, reopened by typing.
    var open by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(SearchState()) }
    val focus = LocalFocusManager.current
    val coordinatesLabel = stringResource(R.string.search_coordinates)
    val geohashLabel = stringResource(R.string.search_geohash)

    LaunchedEffect(query, open) {
        if (!open) return@LaunchedEffect
        val text = query.trim()
        val typed = CoordinateParser.parse(text)
        if (typed != null) {
            state = SearchState(listOf(SearchResult(typed.label(), coordinatesLabel, typed, ResultZoom.POINT)))
            return@LaunchedEffect
        }
        val direct = listOfNotNull(
            CoordinateParser.geohash(text)?.let {
                SearchResult(text.lowercase(), "$geohashLabel · ${it.label()}", it, ResultZoom.POINT)
            },
        )
        if (text.length < IgnGeocoder.queryLength.first) {
            state = SearchState(direct)
            return@LaunchedEffect
        }
        // The previous results stay on screen while the new ones are fetched.
        state = state.copy(loading = true)
        delay(TYPING_PAUSE_MS)
        state = try {
            SearchState(direct + IgnGeocoder.search(text.take(IgnGeocoder.queryLength.last)))
        } catch (_: IOException) {
            SearchState(direct + addresses.search(text), offline = true)
        } catch (_: JSONException) {
            SearchState(direct + addresses.search(text), offline = true)
        }
    }

    fun pick(result: SearchResult) {
        open = false
        query = result.title
        focus.clearFocus()
        onResult(result)
    }

    BackHandler(enabled = open) {
        open = false
        focus.clearFocus()
    }

    Column(modifier) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 3.dp,
        ) {
            TextField(
                value = query,
                onValueChange = {
                    query = it
                    open = it.isNotBlank()
                    if (it.isEmpty()) onCleared()
                },
                placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1) },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                query = ""
                                open = false
                                onCleared()
                            },
                        ) {
                            Text("✕", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // While a search runs, the results on screen belong to the previous text.
                keyboardActions = KeyboardActions(onSearch = { if (!state.loading) state.results.firstOrNull()?.let(::pick) }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused && query.isNotBlank()) open = true },
            )
        }

        val message = when {
            state.offline && !addresses.ready -> stringResource(R.string.search_preparing)
            state.offline && state.results.isEmpty() -> stringResource(R.string.search_offline_empty)
            state.offline -> stringResource(R.string.search_offline)
            state.results.isNotEmpty() -> null
            state.loading -> stringResource(R.string.search_loading)
            query.trim().length >= IgnGeocoder.queryLength.first -> stringResource(R.string.search_empty)
            else -> null
        }
        if (open && (state.results.isNotEmpty() || message != null)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 3.dp,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth(),
            ) {
                Column(
                    Modifier
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    state.results.forEachIndexed { index, result ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { pick(result) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(result.title, style = MaterialTheme.typography.bodyLarge)
                            if (result.subtitle.isNotEmpty()) {
                                Text(
                                    result.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (message != null) {
                        if (state.results.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

/** "48.85660, 2.35220": five decimals, about one metre. */
private fun GeoPoint.label(): String = String.format(Locale.ROOT, "%.5f, %.5f", lat, lon)
