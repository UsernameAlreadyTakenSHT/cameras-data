package io.github.usernamealreadytakensht.cameras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraDataTest {
    private fun camera(id: String, lat: Double, lon: Double, kind: CameraKind = CameraKind.FIXED) =
        Camera(id, lat, lon, kind, "", "", "")

    @Test
    fun readsALineOfTheBundledFile() {
        assertEquals(
            Camera("n218117881", 48.8569481, 2.3497138, CameraKind.DOME, "public", "135", "Préfecture de Police"),
            CameraData.decode("n218117881\t48.8569481\t2.3497138\tdome\tpublic\t135\tPréfecture de Police"),
        )
        // Empty values stay empty.
        assertEquals(
            Camera("w42", 45.76, 4.83, CameraKind.ALPR, "", "", ""),
            CameraData.decode("w42\t45.76\t4.83\talpr\t\t\t"),
        )
    }

    @Test
    fun ignoresBrokenLines() {
        assertNull(CameraData.decode(""))
        assertNull(CameraData.decode("n1\t48.85\t2.35"))
        assertNull(CameraData.decode("n1\tabc\t2.35\tfixed\t\t\t"))
        // A kind unknown to this version of the app is still shown.
        assertEquals(CameraKind.UNKNOWN, CameraData.decode("n1\t48.85\t2.35\tdrone\t\t\t")?.kind)
    }

    @Test
    fun tellsTheKindsApart() {
        assertEquals(CameraKind.FIXED, CameraData.kind("camera", "fixed"))
        assertEquals(CameraKind.PANNING, CameraData.kind("camera", "panning"))
        assertEquals(CameraKind.DOME, CameraData.kind("camera", "Dome"))
        assertEquals(CameraKind.UNKNOWN, CameraData.kind("", ""))
        // The type of surveillance comes before the type of camera.
        assertEquals(CameraKind.GUARD, CameraData.kind("guard", "fixed"))
        assertEquals(CameraKind.ALPR, CameraData.kind("ALPR", "fixed"))
        // tools/build_data.py writes exactly these codes.
        assertEquals(listOf("unknown", "fixed", "panning", "dome", "guard", "alpr"), CameraKind.entries.map { it.code })
    }

    @Test
    fun readsAnOverpassAnswer() {
        val json = """
            {"version": 0.6, "elements": [
              {"type": "node", "id": 218117881, "lat": 48.8569481, "lon": 2.3497138,
               "tags": {"man_made": "surveillance", "surveillance": "public", "surveillance:type": "camera",
                        "camera:type": "dome", "camera:direction": "135", "operator": "Préfecture de Police"}},
              {"type": "way", "id": 42, "center": {"lat": 45.76, "lon": 4.83},
               "tags": {"man_made": "surveillance", "surveillance:type": "ALPR", "direction": "NW"}},
              {"type": "node", "id": 7, "lat": 43.3, "lon": 5.4},
              {"type": "relation", "id": 9, "tags": {"man_made": "surveillance"}}
            ]}
        """.trimIndent()
        assertEquals(
            listOf(
                Camera("n218117881", 48.8569481, 2.3497138, CameraKind.DOME, "public", "135", "Préfecture de Police"),
                // A way is placed at its centre; "direction" stands in for "camera:direction".
                Camera("w42", 45.76, 4.83, CameraKind.ALPR, "", "NW", ""),
                // No tags at all: still a surveillance object, of unknown kind.
                Camera("n7", 43.3, 5.4, CameraKind.UNKNOWN, "", "", ""),
                // The relation without a centre has no position: left out.
            ),
            CameraData.parseOverpass(json),
        )
        assertEquals(emptyList<Camera>(), CameraData.parseOverpass("{}"))
    }

    @Test
    fun asksForTheAreaOnScreen() {
        assertEquals(
            "[out:json][timeout:25];nwr[\"man_made\"=\"surveillance\"](48.85000,2.34000,48.86000,2.36000);out center tags;",
            CameraData.query(GeoBox(48.85, 2.34, 48.86, 2.36)),
        )
    }

    @Test
    fun freshCamerasReplaceTheBundledOnesInsideTheArea() {
        val box = GeoBox(48.0, 2.0, 49.0, 3.0)
        val outside = camera("n1", 45.0, 4.0)
        val removed = camera("n2", 48.5, 2.5)
        val changed = camera("n3", 48.6, 2.6)
        // A way whose centre moved out of the area between the two readings: it must not appear twice.
        val moved = camera("w4", 49.5, 2.5)
        val merged = CameraData.merge(
            current = listOf(outside, removed, changed, moved),
            box = box,
            fresh = listOf(changed.copy(kind = CameraKind.DOME), camera("n5", 48.7, 2.7), moved.copy(lat = 48.9)),
        )
        assertEquals(
            listOf(outside, changed.copy(kind = CameraKind.DOME), camera("n5", 48.7, 2.7), moved.copy(lat = 48.9)),
            merged,
        )
    }

    @Test
    fun knowsWhenAnAreaIsAlreadyCovered() {
        val box = GeoBox(48.0, 2.0, 49.0, 3.0)
        assertEquals(true, box.contains(GeoBox(48.2, 2.2, 48.8, 2.8)))
        assertEquals(true, box.contains(box))
        assertEquals(false, box.contains(GeoBox(48.2, 2.2, 49.1, 2.8)))
    }
}
