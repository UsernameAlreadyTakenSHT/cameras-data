package io.github.usernamealreadytakensht.cameras

import android.graphics.Color as AndroidColor
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** What a touch on the map hit, among the points drawn over the base map. */
sealed interface Touched {
    data class OfRadar(val index: Int) : Touched
    data class OfCamera(val index: Int) : Touched
}

/** The points drawn over the base map: fixed speed cameras and surveillance cameras. */
object Overlays {
    private const val RADARS = "radars"
    private const val CAMERAS = "cameras"
    private const val CAMERA_CLUSTERS = "camera-clusters"

    /** Property of each point: position of its object in the list shown. */
    private const val INDEX = "index"
    private const val KIND = "kind"

    /** Property MapLibre gives to a cluster: how many points it stands for. */
    private const val POINT_COUNT = "point_count"

    /** Map zoom up to which the cameras are gathered into clusters. */
    private const val CLUSTER_MAX_ZOOM = 13

    private const val WHITE = "#FFFFFF"

    /** Draws [radars] as orange dots, replacing those already there. */
    fun showRadars(style: Style, radars: List<Radar>) {
        style.removeLayer(RADARS)
        style.removeSource(RADARS)
        if (radars.isEmpty()) return
        val features = radars.mapIndexed { index, radar ->
            Feature.fromGeometry(Point.fromLngLat(radar.lon, radar.lat)).apply { addNumberProperty(INDEX, index) }
        }
        style.addSource(GeoJsonSource(RADARS, FeatureCollection.fromFeatures(features)))
        style.addLayer(
            CircleLayer(RADARS, RADARS).withProperties(
                PropertyFactory.circleColor("#F08C00"),
                PropertyFactory.circleStrokeColor(WHITE),
                PropertyFactory.circleStrokeWidth(1.5f),
                // Small over the whole country, easy to touch at street level.
                PropertyFactory.circleRadius(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(4, 2.5f), Expression.stop(10, 5f), Expression.stop(15, 9f),
                    ),
                ),
            ),
        )
    }

    /**
     * Draws [cameras], one colour per [CameraKind]. There are tens of thousands of them: below
     * street level they are gathered into dark clusters, larger where there are more.
     */
    suspend fun showCameras(style: Style, cameras: List<Camera>) {
        if (cameras.isEmpty()) {
            style.removeLayer(CAMERAS)
            style.removeLayer(CAMERA_CLUSTERS)
            style.removeSource(CAMERAS)
            return
        }
        val collection = withContext(Dispatchers.Default) {
            FeatureCollection.fromFeatures(
                cameras.mapIndexed { index, camera ->
                    Feature.fromGeometry(Point.fromLngLat(camera.lon, camera.lat)).apply {
                        addNumberProperty(INDEX, index)
                        addStringProperty(KIND, camera.kind.code)
                    }
                },
            )
        }
        // Already drawn: only the points change (an area was brought up to date), the layers stay.
        val existing = style.getSourceAs<GeoJsonSource>(CAMERAS)
        if (existing != null) {
            existing.setGeoJson(collection)
            return
        }
        style.addSource(
            GeoJsonSource(
                CAMERAS,
                collection,
                GeoJsonOptions().withCluster(true).withClusterMaxZoom(CLUSTER_MAX_ZOOM).withClusterRadius(45),
            ),
        )
        val clusters = CircleLayer(CAMERA_CLUSTERS, CAMERAS).withProperties(
            PropertyFactory.circleColor("#343A40"),
            PropertyFactory.circleOpacity(0.8f),
            PropertyFactory.circleStrokeColor(WHITE),
            PropertyFactory.circleStrokeWidth(1.5f),
            PropertyFactory.circleRadius(
                Expression.step(
                    Expression.get(POINT_COUNT), Expression.literal(8f),
                    Expression.stop(10, 11f), Expression.stop(100, 15f), Expression.stop(1000, 20f),
                ),
            ),
        )
        clusters.setFilter(Expression.has(POINT_COUNT))
        val points = CircleLayer(CAMERAS, CAMERAS).withProperties(
            PropertyFactory.circleColor(
                Expression.match(
                    Expression.get(KIND),
                    Expression.color(AndroidColor.parseColor(CameraKind.UNKNOWN.color)),
                    *CameraKind.entries
                        .map { Expression.stop(it.code, Expression.color(AndroidColor.parseColor(it.color))) }
                        .toTypedArray(),
                ),
            ),
            PropertyFactory.circleStrokeColor(WHITE),
            PropertyFactory.circleStrokeWidth(1.5f),
            PropertyFactory.circleRadius(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(10, 4f), Expression.stop(16, 8f),
                ),
            ),
        )
        points.setFilter(Expression.not(Expression.has(POINT_COUNT)))
        // Under the speed cameras when they are shown: those are fewer, and must stay reachable.
        if (style.getLayer(RADARS) != null) {
            style.addLayerBelow(clusters, RADARS)
            style.addLayerBelow(points, RADARS)
        } else {
            style.addLayer(clusters)
            style.addLayer(points)
        }
    }

    /**
     * What lies within [radius] pixels of [position]. Touching a cluster zooms into it and
     * reports nothing.
     *
     * @return null when the touch hit none of the points.
     */
    fun touched(map: MapLibreMap, position: LatLng, radius: Float): Touched? {
        val touch = map.projection.toScreenLocation(position)
        val area = RectF(touch.x - radius, touch.y - radius, touch.x + radius, touch.y + radius)
        val hit = map.queryRenderedFeatures(area, RADARS, CAMERAS, CAMERA_CLUSTERS).firstOrNull() ?: return null
        return when {
            hit.hasProperty(POINT_COUNT) -> {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(position, map.cameraPosition.zoom + 2), 500)
                null
            }
            hit.hasProperty(KIND) -> Touched.OfCamera(hit.getNumberProperty(INDEX).toInt())
            else -> Touched.OfRadar(hit.getNumberProperty(INDEX).toInt())
        }
    }
}
