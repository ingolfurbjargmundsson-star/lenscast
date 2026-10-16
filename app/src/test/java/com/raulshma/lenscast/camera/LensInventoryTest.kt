package com.raulshma.lenscast.camera

import androidx.camera.core.CameraSelector
import com.raulshma.lenscast.camera.model.CameraLensInfo
import com.raulshma.lenscast.camera.model.LensLabelKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class LensInventoryTest {

    private fun lens(
        id: String,
        facing: Int = CameraSelector.LENS_FACING_BACK,
        focal: Float = 4.3f,
        kind: LensLabelKind = LensInventory.labelKind(facing, focal),
        physicalCameraId: String? = null,
        zoomRatio: Float? = null,
    ) = CameraLensInfo(
        id = id,
        labelKind = kind,
        lensFacing = facing,
        focalLength = focal,
        cameraSelector = if (facing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        },
        physicalCameraId = physicalCameraId,
        zoomRatio = zoomRatio,
    )

    @Test
    fun `main front lens folds to front regardless of wide or unknown focal`() {
        assertEquals(LensLabelKind.FRONT, LensInventory.labelKind(CameraSelector.LENS_FACING_FRONT, 3.5f))
        assertEquals(LensLabelKind.FRONT, LensInventory.labelKind(CameraSelector.LENS_FACING_FRONT, 4.3f))
        assertEquals(LensLabelKind.FRONT, LensInventory.labelKind(CameraSelector.LENS_FACING_FRONT, 0f))
        // An extreme front focal is a zoom — "Front 23x", not a bare "Front".
        assertEquals(LensLabelKind.ZOOM, LensInventory.labelKind(CameraSelector.LENS_FACING_FRONT, 99f))
    }

    @Test
    fun `front ultrawide keeps its band so dual-front phones distinguish lenses`() {
        assertEquals(
            LensLabelKind.ULTRAWIDE,
            LensInventory.labelKind(CameraSelector.LENS_FACING_FRONT, 2.2f),
        )
        assertEquals(
            LensLabelKind.ZOOM,
            LensInventory.labelKind(CameraSelector.LENS_FACING_FRONT, 6f),
        )
    }

    @Test
    fun `back kinds follow focal length bands with one generic zoom`() {
        val back = CameraSelector.LENS_FACING_BACK
        assertEquals(LensLabelKind.CAMERA_ID, LensInventory.labelKind(back, 0f))
        assertEquals(LensLabelKind.CAMERA_ID, LensInventory.labelKind(back, -1f))
        assertEquals(LensLabelKind.ULTRAWIDE, LensInventory.labelKind(back, 1.8f))
        assertEquals(LensLabelKind.WIDE, LensInventory.labelKind(back, 4.3f))
        // Any telephoto — 2x, 3x, 5x, 10x — shares ZOOM; the ratio disambiguates.
        assertEquals(LensLabelKind.ZOOM, LensInventory.labelKind(back, 6f))
        assertEquals(LensLabelKind.ZOOM, LensInventory.labelKind(back, 12f))
        assertEquals(LensLabelKind.ZOOM, LensInventory.labelKind(back, 20f))
        assertEquals(LensLabelKind.ZOOM, LensInventory.labelKind(back, 30f))
    }

    @Test
    fun `back bands switch exactly at the focal thresholds`() {
        val back = CameraSelector.LENS_FACING_BACK
        assertEquals(LensLabelKind.ULTRAWIDE, LensInventory.labelKind(back, 2.49f))
        assertEquals(LensLabelKind.WIDE, LensInventory.labelKind(back, 2.5f))
        assertEquals(LensLabelKind.WIDE, LensInventory.labelKind(back, 2.51f))
        assertEquals(LensLabelKind.WIDE, LensInventory.labelKind(back, 4.99f))
        assertEquals(LensLabelKind.ZOOM, LensInventory.labelKind(back, 5f))
        assertEquals(LensLabelKind.ZOOM, LensInventory.labelKind(back, 5.01f))
    }

    @Test
    fun `english base label mirrors the kind mapping`() {
        val back = CameraSelector.LENS_FACING_BACK
        val front = CameraSelector.LENS_FACING_FRONT
        assertEquals("Front", LensInventory.baseLabel(front, LensLabelKind.FRONT, 4.3f, "1"))
        assertEquals("Front Ultrawide", LensInventory.baseLabel(front, LensLabelKind.ULTRAWIDE, 2.2f, "1"))
        assertEquals("Front 2x", LensInventory.baseLabel(front, LensLabelKind.ZOOM, 8.6f, "1", 2f))
        assertEquals("Front 3.9mm", LensInventory.baseLabel(front, LensLabelKind.FOCAL_MM, 3.9f, "1"))
        assertEquals("Ultrawide", LensInventory.baseLabel(back, LensLabelKind.ULTRAWIDE, 1.8f, "0"))
        assertEquals("Wide", LensInventory.baseLabel(back, LensLabelKind.WIDE, 4.3f, "0"))
        assertEquals("2x", LensInventory.baseLabel(back, LensLabelKind.ZOOM, 8.6f, "0", 2f))
        assertEquals("3.4x", LensInventory.baseLabel(back, LensLabelKind.ZOOM, 14.6f, "0", 3.4f))
        // Unknown 1x reference falls back to the focal so the chip never blanks.
        assertEquals("12mm", LensInventory.baseLabel(back, LensLabelKind.ZOOM, 12f, "0", null))
        assertEquals("24mm", LensInventory.baseLabel(back, LensLabelKind.FOCAL_MM, 24f, "0"))
        assertEquals("Camera 0", LensInventory.baseLabel(back, LensLabelKind.CAMERA_ID, 0f, "0"))
        assertEquals("Back", LensInventory.baseLabel(back, LensLabelKind.BACK, 0f, "0"))
    }

    @Test
    fun `zoom format trims to one locale-aware decimal`() {
        assertEquals("2x", LensInventory.formatZoom(2f))
        assertEquals("2x", LensInventory.formatZoom(2.02f))
        assertEquals("2.3x", LensInventory.formatZoom(2.34f))
        assertEquals("2,3x", LensInventory.formatZoom(2.34f, Locale.GERMAN))
    }

    @Test
    fun `focal format trims to one locale-aware decimal`() {
        assertEquals("24mm", LensInventory.formatFocal(24f))
        assertEquals("3.9mm", LensInventory.formatFocal(3.94f))
        assertEquals("3,9mm", LensInventory.formatFocal(3.94f, Locale.GERMAN))
    }

    @Test
    fun `reference wide focal is the smallest non-ultrawide back focal`() {
        val inventory = listOf(
            lens("ultra", focal = 1.8f),
            lens("wide", focal = 4.3f),
            lens("tele", focal = 8.6f),
            lens("front", facing = CameraSelector.LENS_FACING_FRONT, focal = 4.0f),
        )
        assertEquals(4.3f, LensInventory.referenceWideFocal(inventory))
    }

    @Test
    fun `assignZoomRatios fills telephoto ratios relative to the main wide lens`() {
        val inventory = listOf(
            lens("ultra", focal = 1.8f),
            lens("wide", focal = 4.3f),
            lens("tele2x", focal = 8.6f),
            lens("tele3x", focal = 12.9f),
        )
        val assigned = LensInventory.assignZoomRatios(inventory)
        assertNull(assigned[0].zoomRatio)
        assertNull(assigned[1].zoomRatio)
        assertEquals(2f, assigned[2].zoomRatio!!, 0.001f)
        assertEquals(3f, assigned[3].zoomRatio!!, 0.001f)
    }

    @Test
    fun `physical lens rendering the same label as its logical sibling drops to focal`() {
        // The F-Droid review case: a logical front camera and its same-band
        // physical one both resolving to FRONT — the physical chip must not
        // read as a duplicate.
        val logical = lens("1", facing = CameraSelector.LENS_FACING_FRONT, focal = 4.3f)
        val physical = lens("2", facing = CameraSelector.LENS_FACING_FRONT, focal = 3.9f, physicalCameraId = "2")
        val result = LensInventory.disambiguate(listOf(logical, physical))
        assertEquals(LensLabelKind.FRONT, result[0].labelKind)
        assertEquals(LensLabelKind.FOCAL_MM, result[1].labelKind)
        assertEquals("Front 3.9mm", LensInventory.baseLabel(result[1].lensFacing, result[1].labelKind, result[1].focalLength, result[1].id))
    }

    @Test
    fun `same-band physical back camera is disambiguated the same way`() {
        val logical = lens("0", focal = 4.3f)
        val physical = lens("20", focal = 3.9f, physicalCameraId = "20")
        val result = LensInventory.disambiguate(listOf(logical, physical))
        assertEquals(LensLabelKind.WIDE, result[0].labelKind)
        assertEquals(LensLabelKind.FOCAL_MM, result[1].labelKind)
    }

    @Test
    fun `zooms with different ratios never collide but same-ratio siblings do`() {
        val wide = lens("0", focal = 4.3f)
        val tele2x = lens("1", focal = 8.6f, zoomRatio = 2f)
        val tele3x = lens("2", focal = 12.9f, physicalCameraId = "2", zoomRatio = 3f)
        val distinct = LensInventory.disambiguate(listOf(wide, tele2x, tele3x))
        assertEquals(LensLabelKind.ZOOM, distinct[1].labelKind)
        assertEquals(LensLabelKind.ZOOM, distinct[2].labelKind)

        val dupTele = lens("3", focal = 8.7f, physicalCameraId = "3", zoomRatio = 2.02f)
        val collided = LensInventory.disambiguate(listOf(wide, tele2x, dupTele))
        assertEquals(LensLabelKind.FOCAL_MM, collided[2].labelKind)
    }

    @Test
    fun `disambiguation leaves distinct lenses and logical cameras untouched`() {
        val inventory = listOf(
            lens("0", focal = 1.8f),
            lens("1", focal = 4.3f),
            lens("front", facing = CameraSelector.LENS_FACING_FRONT, focal = 4.3f),
            lens("frontUltra", facing = CameraSelector.LENS_FACING_FRONT, focal = 2.2f, physicalCameraId = "3"),
        )
        val result = LensInventory.disambiguate(inventory)
        assertEquals(inventory.map { it.labelKind }, result.map { it.labelKind })
    }

    @Test
    fun `oem duplicates collapse on facing plus focal length`() {
        val lenses = listOf(
            lens("0", focal = 4.3f),
            lens("dup", focal = 4.3f),
            lens("front", facing = CameraSelector.LENS_FACING_FRONT, focal = 4.3f),
        )
        val distinct = LensInventory.deduplicate(lenses)
        assertEquals(listOf("0", "front"), distinct.map { it.id })
    }

    @Test
    fun `sort puts back lenses first by ascending focal length`() {
        val lenses = listOf(
            lens("front", facing = CameraSelector.LENS_FACING_FRONT, focal = 2f),
            lens("tele", focal = 12f),
            lens("wide", focal = 4.3f),
            lens("ultra", focal = 1.8f),
        )
        val sorted = LensInventory.sortLenses(lenses)
        assertEquals(listOf("ultra", "wide", "tele", "front"), sorted.map { it.id })
    }

    @Test
    fun `default is the first back lens`() {
        val sorted = LensInventory.sortLenses(
            listOf(
                lens("front", facing = CameraSelector.LENS_FACING_FRONT),
                lens("wide", focal = 4.3f),
                lens("ultra", focal = 1.8f),
            ),
        )
        assertEquals(0, LensInventory.defaultBackIndex(sorted))
    }

    @Test
    fun `default without any back lens is zero`() {
        assertEquals(0, LensInventory.defaultBackIndex(emptyList()))
        assertEquals(
            0,
            LensInventory.defaultBackIndex(
                listOf(lens("front", facing = CameraSelector.LENS_FACING_FRONT)),
            ),
        )
    }

    @Test
    fun `fallback covers back and front`() {
        val fallback = LensInventory.fallbackLenses()
        assertEquals(2, fallback.size)
        assertEquals(LensLabelKind.BACK, fallback[0].labelKind)
        assertEquals(LensLabelKind.FRONT, fallback[1].labelKind)
        assertEquals(0, LensInventory.defaultBackIndex(fallback))
    }

    @Test
    fun `next index advances and wraps around the inventory`() {
        assertEquals(1, LensInventory.nextIndex(0, 4))
        assertEquals(3, LensInventory.nextIndex(2, 4))
        assertEquals(0, LensInventory.nextIndex(3, 4))
        // A single-lens inventory always cycles back to itself.
        assertEquals(0, LensInventory.nextIndex(0, 1))
    }

    @Test
    fun `empty-inventory fallback toggles front to back`() {
        assertEquals(
            CameraSelector.DEFAULT_BACK_CAMERA to false,
            LensInventory.fallbackSelector(currentFront = true),
        )
    }

    @Test
    fun `empty-inventory fallback toggles back to front`() {
        assertEquals(
            CameraSelector.DEFAULT_FRONT_CAMERA to true,
            LensInventory.fallbackSelector(currentFront = false),
        )
    }
}
