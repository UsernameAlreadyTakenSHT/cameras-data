package io.github.usernamealreadytakensht.cameras

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

private const val PREFS = "map"

/** Map zoom from which the cameras on screen are brought up to date online. */
private const val REFRESH_MIN_ZOOM = 13.5

/** How long the map must stay still before that request is sent. */
private const val REFRESH_PAUSE_MS = 800L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val offline = remember { OfflineMaps(context.applicationContext) }
    val files = remember { DataFiles(context.applicationContext) }
    val addresses = remember { AddressStore(context.applicationContext, files) }
    val radarStore = remember { RadarStore(files) }
    val cameraStore = remember { CameraStore(files) }
    // Once a month, the data that came with the app is renewed if newer files were published.
    LaunchedEffect(Unit) {
        DataUpdater(context.applicationContext, files, radarStore, cameraStore, addresses, offline).runIfDue()
    }
    val regions by produceState(emptyList<Region>()) { value = Regions.load(context.applicationContext) }
    var layer by remember {
        mutableStateOf(MapLayer.byId(prefs.getString("layer", null)) ?: MapLayer.PLAN)
    }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var layersOpen by remember { mutableStateOf(false) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    // The place picked in the search, shown by a marker.
    var found by remember { mutableStateOf<GeoPoint?>(null) }
    // Bumped at every camera move, so that the marker follows the map.
    var cameraMoves by remember { mutableIntStateOf(0) }
    // Bumped each time the map comes to rest.
    var cameraStops by remember { mutableIntStateOf(0) }

    var radarsOn by remember { mutableStateOf(prefs.getBoolean("radars", false)) }
    var camerasOn by remember { mutableStateOf(prefs.getBoolean("cameras", false)) }
    val shownRadars = if (radarsOn) radarStore.radars else emptyList()
    val shownCameras = if (camerasOn) cameraStore.cameras else emptyList()
    // Street photo services: off until ticked, and no request is ever sent while they are off.
    var panoramaxOn by remember { mutableStateOf(prefs.getBoolean("panoramax", false)) }
    var mapillaryOn by remember { mutableStateOf(prefs.getBoolean("mapillary", false)) }
    var mapillaryToken by remember { mutableStateOf(prefs.getString("mapillary_token", "").orEmpty()) }
    var tokenDialog by remember { mutableStateOf(false) }
    // Where the map was long-pressed: the closest photo is shown in a panel.
    var photoAt by remember { mutableStateOf<GeoPoint?>(null) }
    var cacheBytes by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    val photosOn by rememberUpdatedState(panoramaxOn || (mapillaryOn && mapillaryToken.isNotEmpty()))
    // The point touched on the map, described by a card: a Radar or a Camera.
    var touched by remember { mutableStateOf<Any?>(null) }

    // With a connection, the cameras of the area on screen are brought up to date: only at
    // neighbourhood scale, where the area is small, and once the map has stayed still a moment.
    LaunchedEffect(cameraStops, camerasOn, map, cameraStore.cameras.isEmpty()) {
        val shown = map ?: return@LaunchedEffect
        if (!camerasOn) return@LaunchedEffect
        delay(REFRESH_PAUSE_MS)
        if (shown.cameraPosition.zoom < REFRESH_MIN_ZOOM) return@LaunchedEffect
        val bounds = shown.projection.visibleRegion.latLngBounds
        cameraStore.refresh(GeoBox(bounds.latitudeSouth, bounds.longitudeWest, bounds.latitudeNorth, bounds.longitudeEast))
    }

    // A long download must not be cut by the screen going to sleep.
    val view = LocalView.current
    val downloading = offline.progress?.manual == true
    DisposableEffect(view, downloading) {
        view.keepScreenOn = downloading
        onDispose { view.keepScreenOn = false }
    }

    Box(Modifier.fillMaxSize()) {
        BaseMap(
            layer = layer,
            radars = shownRadars,
            cameras = shownCameras,
            onLongPress = { point -> if (photosOn) photoAt = point },
            onTouched = { hit ->
                touched = when (hit) {
                    is Touched.OfRadar -> shownRadars.getOrNull(hit.index)
                    is Touched.OfCamera -> shownCameras.getOrNull(hit.index)
                    null -> null
                }
            },
        ) { ready ->
            ready.addOnCameraMoveListener { cameraMoves++ }
            ready.addOnCameraIdleListener { cameraStops++ }
            map = ready
        }

        found?.let { point -> map?.let { Marker(it, point, cameraMoves) } }

        // Sources and offline button share the bottom line: the sources wrap rather than run under the button.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 6.dp, end = 12.dp, bottom = 6.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Attribution(
                layer = layer,
                radars = shownRadars.isNotEmpty(),
                cameras = shownCameras.isNotEmpty(),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(end = 8.dp),
            )
            ElevatedButton(
                onClick = {
                    offline.refresh()
                    sheetOpen = true
                },
            ) {
                Text(stringResource(R.string.offline_button), maxLines = 1)
            }
        }

        val cardModifier = Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = 12.dp, end = 12.dp, bottom = 84.dp)
            .fillMaxWidth()
        when (val point = touched) {
            is Radar -> RadarCard(point, onClose = { touched = null }, modifier = cardModifier)
            is Camera -> CameraCard(point, onClose = { touched = null }, modifier = cardModifier)
        }

        // Last, so that the search results cover the buttons below them.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchBar(
                addresses = addresses,
                onResult = { result ->
                    found = result.point
                    map?.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(result.point.lat, result.point.lon))
                                .zoom(result.zoom)
                                .build(),
                        ),
                        800,
                    )
                },
                onCleared = { found = null },
                modifier = Modifier.weight(1f),
            )

            // The base maps and the overlays, gathered behind one "layers" button.
            Box(Modifier.padding(top = 4.dp)) {
                FilledTonalIconButton(
                    onClick = { layersOpen = true },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier
                        .shadow(3.dp, CircleShape)
                        .size(48.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_layers), stringResource(R.string.layers))
                }
                DropdownMenu(expanded = layersOpen, onDismissRequest = { layersOpen = false }) {
                    MapLayer.entries.forEach { choice ->
                        DropdownMenuItem(
                            leadingIcon = { RadioButton(selected = choice == layer, onClick = null) },
                            text = { Text(stringResource(choice.fullName)) },
                            onClick = {
                                layersOpen = false
                                layer = choice
                                prefs.edit().putString("layer", choice.id).apply()
                            },
                        )
                    }
                    HorizontalDivider()
                    OverlayItem(
                        checked = camerasOn,
                        title = stringResource(R.string.cameras_title),
                        status = stringResource(
                            if (cameraStore.updatedOnline) R.string.cameras_ready_online else R.string.cameras_ready,
                            cameraStore.cameras.size, cameraStore.date,
                        ),
                    ) {
                        layersOpen = false
                        camerasOn = !camerasOn
                        prefs.edit().putBoolean("cameras", camerasOn).apply()
                        if (!camerasOn && touched is Camera) touched = null
                    }
                    OverlayItem(
                        checked = radarsOn,
                        title = stringResource(R.string.radars_title),
                        status = stringResource(R.string.radars_ready, radarStore.radars.size, radarStore.date),
                    ) {
                        layersOpen = false
                        radarsOn = !radarsOn
                        prefs.edit().putBoolean("radars", radarsOn).apply()
                        if (!radarsOn && touched is Radar) touched = null
                    }
                    HorizontalDivider()
                    OverlayItem(
                        checked = panoramaxOn,
                        title = stringResource(R.string.photos_panoramax),
                        status = stringResource(if (panoramaxOn) R.string.photos_hint else R.string.photos_off),
                    ) {
                        layersOpen = false
                        panoramaxOn = !panoramaxOn
                        prefs.edit().putBoolean("panoramax", panoramaxOn).apply()
                    }
                    OverlayItem(
                        checked = mapillaryOn && mapillaryToken.isNotEmpty(),
                        title = stringResource(R.string.photos_mapillary),
                        status = stringResource(
                            when {
                                mapillaryOn && mapillaryToken.isNotEmpty() -> R.string.photos_hint
                                else -> R.string.photos_off_token
                            },
                        ),
                    ) {
                        layersOpen = false
                        if (mapillaryOn && mapillaryToken.isNotEmpty()) {
                            mapillaryOn = false
                            prefs.edit().putBoolean("mapillary", false).apply()
                        } else {
                            // Turned on only once a token is given.
                            tokenDialog = true
                        }
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        leadingIcon = { Spacer(Modifier.size(24.dp)) },
                        text = {
                            Column {
                                Text(stringResource(R.string.cache_clear))
                                Text(
                                    stringResource(R.string.cache_size, Formatter.formatShortFileSize(context, cacheBytes)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            layersOpen = false
                            scope.launch(Dispatchers.IO) { httpCache.evictAll() }
                        },
                    )
                }
            }
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
            OfflineSheet(offline, regions, layer)
        }
    }

    photoAt?.let { point ->
        ModalBottomSheet(onDismissRequest = { photoAt = null }) {
            PhotoSheet(point, panoramaxOn, mapillaryToken.takeIf { mapillaryOn && it.isNotEmpty() })
        }
    }

    if (tokenDialog) {
        MapillaryTokenDialog(
            current = mapillaryToken,
            onDone = { token ->
                tokenDialog = false
                mapillaryToken = token
                mapillaryOn = token.isNotEmpty()
                prefs.edit().putString("mapillary_token", token).putBoolean("mapillary", mapillaryOn).apply()
            },
            onDismiss = { tokenDialog = false },
        )
    }

    // The size shown in the menu is read each time it opens.
    LaunchedEffect(layersOpen) {
        if (layersOpen) cacheBytes = withContext(Dispatchers.IO) { httpCache.size() }
    }
}

/** One overlay of the layers menu: a box to tick, its name and the state of its data. */
@Composable
private fun OverlayItem(checked: Boolean, title: String, status: String, onClick: () -> Unit) {
    DropdownMenuItem(
        leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
        text = {
            Column {
                Text(title)
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        onClick = onClick,
    )
}

/**
 * The MapLibre view showing [layer], with [radars] and [cameras] drawn over it. The camera stays
 * put when the layer changes.
 */
@Composable
private fun BaseMap(
    layer: MapLayer,
    radars: List<Radar>,
    cameras: List<Camera>,
    onLongPress: (GeoPoint) -> Unit,
    onTouched: (Touched?) -> Unit,
    onReady: (MapLibreMap) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val currentOnTouched by rememberUpdatedState(onTouched)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val touchRadius = with(LocalDensity.current) { 14.dp.toPx() }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    // Set once the style of the layer is loaded: overlays can only be added to a loaded style.
    var style by remember { mutableStateOf<Style?>(null) }

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { ready ->
                ready.setMaxZoomPreference(20.0)
                ready.uiSettings.apply {
                    // The attribution is drawn by the screen itself, always visible.
                    isLogoEnabled = false
                    isAttributionEnabled = false
                    isCompassEnabled = false
                    isRotateGesturesEnabled = false
                    isTiltGesturesEnabled = false
                }
                // Last position, or the whole of metropolitan France on first launch.
                ready.cameraPosition = CameraPosition.Builder()
                    .target(
                        LatLng(
                            prefs.getFloat("lat", 46.6f).toDouble(),
                            prefs.getFloat("lon", 2.5f).toDouble(),
                        ),
                    )
                    .zoom(prefs.getFloat("zoom", 4.3f).toDouble())
                    .build()
                ready.addOnMapClickListener { position ->
                    val hit = Overlays.touched(ready, position, touchRadius)
                    currentOnTouched(hit)
                    hit != null
                }
                ready.addOnMapLongClickListener { position ->
                    currentOnLongPress(GeoPoint(position.latitude, position.longitude))
                    true
                }
                map = ready
                onReady(ready)
            }
        }
    }

    LaunchedEffect(map, layer) {
        style = null
        map?.setStyle(Style.Builder().fromUri(layer.styleUrl)) { style = it }
    }

    // A new base map means a new style: the overlays are put back on each of them.
    LaunchedEffect(style, radars) {
        style?.let { Overlays.showRadars(it, radars) }
    }
    LaunchedEffect(style, cameras) {
        style?.let { Overlays.showCameras(it, cameras) }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                    map?.cameraPosition?.let { camera ->
                        val target = camera.target
                        if (target != null) {
                            prefs.edit()
                                .putFloat("lat", target.latitude.toFloat())
                                .putFloat("lon", target.longitude.toFloat())
                                .putFloat("zoom", camera.zoom.toFloat())
                                .apply()
                        }
                    }
                    mapView.onPause()
                }
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

/** A dot drawn over the map at [point]. [cameraMoves] changes whenever the map moves. */
@Composable
private fun Marker(map: MapLibreMap, point: GeoPoint, cameraMoves: Int) {
    val screen = remember(point, cameraMoves) { map.projection.toScreenLocation(LatLng(point.lat, point.lon)) }
    val size = 22.dp
    Canvas(
        Modifier
            .offset {
                val half = size.toPx() / 2
                IntOffset((screen.x - half).roundToInt(), (screen.y - half).roundToInt())
            }
            .shadow(3.dp, CircleShape)
            .size(size),
    ) {
        drawCircle(Color.White)
        drawCircle(Color(0xFFD92D20), radius = this.size.minDimension / 2 * 0.68f)
    }
}

/** Sources of what is on screen: the base map, then one line per overlay shown. */
@Composable
private fun Attribution(layer: MapLayer, radars: Boolean, cameras: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Surface(
        modifier = modifier.clickable {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(layer.infoUrl)))
            } catch (_: ActivityNotFoundException) {
                // No browser installed: the text alone is the attribution.
            }
        },
        color = Color.White.copy(alpha = 0.8f),
        contentColor = Color(0xFF1F2933),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = listOfNotNull(
                stringResource(R.string.attribution, stringResource(layer.fullName)),
                if (cameras) stringResource(R.string.cameras_attribution) else null,
                if (radars) stringResource(R.string.radars_attribution) else null,
            ).joinToString("\n"),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun RadarCard(radar: Radar, onClose: () -> Unit, modifier: Modifier = Modifier) {
    InfoCard(
        title = stringResource(radarTypeLabel(radar.type), radar.type),
        details = listOfNotNull(
            radar.speedLimit?.let { stringResource(R.string.radar_speed, it) },
            radar.since.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.radar_since, it) },
            radar.id.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.radar_number, it) },
        ),
        color = Color(0xFFF08C00),
        onClose = onClose,
        modifier = modifier,
    )
}

@Composable
private fun CameraCard(camera: Camera, onClose: () -> Unit, modifier: Modifier = Modifier) {
    InfoCard(
        title = stringResource(camera.kind.label),
        details = listOfNotNull(
            when (camera.zone.lowercase()) {
                "public" -> stringResource(R.string.camera_zone_public)
                "outdoor" -> stringResource(R.string.camera_zone_outdoor)
                "indoor" -> stringResource(R.string.camera_zone_indoor)
                else -> null
            },
            camera.direction.takeIf { it.isNotEmpty() }?.let { direction ->
                // Degrees are written bare in OpenStreetMap; compass points ("NW") stay as they are.
                stringResource(R.string.camera_direction, if (direction.toDoubleOrNull() != null) "$direction°" else direction)
            },
            camera.operator.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.camera_operator, it) },
            stringResource(R.string.camera_source),
        ),
        color = Color(android.graphics.Color.parseColor(camera.kind.color)),
        onClose = onClose,
        modifier = modifier,
    )
}

/** Card describing the point touched on the map, with the dot of its colour. */
@Composable
private fun InfoCard(title: String, details: List<String>, color: Color, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(14.dp)) { drawCircle(color) }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) {
                Text("✕", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
