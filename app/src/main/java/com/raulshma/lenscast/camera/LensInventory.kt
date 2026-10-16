package com.raulshma.lenscast.camera

import androidx.camera.core.CameraSelector
import com.raulshma.lenscast.camera.model.CameraLensInfo
import com.raulshma.lenscast.camera.model.LensLabelKind
import com.raulshma.lenscast.camera.model.ZoomPresetsPolicy
import java.util.Locale

/**
 * The lens-inventory knowledge: label kinds, zoom-ratio assignment, OEM-duplicate
 * removal, the same-label collision pass, ordering, the main-lens default, the
 * switch-cycle index, the empty-inventory front/back toggle, and the
 * enumeration-failure fallback. Pure — the service keeps the provider
 * iteration and the Camera2 reads, and delegates every decision here, so
 * lens behavior is testable without a device.
 *
 * Telephoto lenses share one [LensLabelKind.ZOOM] kind: the magnification
 * rides [CameraLensInfo.zoomRatio] relative to the main wide lens, so a 4x or
 * 10x periscope never needs a new band, enum value, or string.
 */
object LensInventory {

    /**
     * Below this focal (mm) a lens is the ultrawide — never the 1x reference.
     * Kept for API compat; [band] and [referenceWideFocal] read
     * [ZoomPresetsPolicy.ULTRAWIDE_MAX_FOCAL_MM] directly so chips and presets share one source.
     */
    const val ULTRAWIDE_MAX_FOCAL_MM = 2.5f

    /** Below this focal (mm) a lens is the main wide band; at/above is telephoto. */
    const val WIDE_MAX_FOCAL_MM = 5f

    /**
     * The display role for one lens. Front cameras fold the unknown/wide
     * bands into [LensLabelKind.FRONT] (the main selfie camera just reads
     * "Front"); a wider front focal keeps the ultrawide band so dual-front
     * phones distinguish their lenses, and any front telephoto stays
     * [LensLabelKind.ZOOM] so its magnification disambiguates it.
     * Localization happens at the display surfaces; [baseLabel] is the
     * English base for the API and logs.
     */
    fun labelKind(lensFacing: Int, focalLength: Float): LensLabelKind {
        val band = band(focalLength)
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            return if (band == LensLabelKind.WIDE || band == LensLabelKind.CAMERA_ID) {
                LensLabelKind.FRONT
            } else {
                band
            }
        }
        return band
    }

    private fun band(focalLength: Float): LensLabelKind = when {
        focalLength <= 0f -> LensLabelKind.CAMERA_ID
        focalLength < ZoomPresetsPolicy.ULTRAWIDE_MAX_FOCAL_MM -> LensLabelKind.ULTRAWIDE
        focalLength < WIDE_MAX_FOCAL_MM -> LensLabelKind.WIDE
        else -> LensLabelKind.ZOOM
    }

    /**
     * The 1x reference for zoom ratios: the smallest non-ultrawide positive
     * back focal (the main wide lens); when every back lens is ultrawide or
     * unknown, the smallest positive focal. Null with no back-lens info.
     * Delegates to the zoom-preset ladder's reference so chips and presets
     * can never drift apart.
     */
    fun referenceWideFocal(lenses: List<CameraLensInfo>): Float? =
        ZoomPresetsPolicy.mainFocalLength(lenses)

    /** Magnification of [focal] against the 1x [reference]; null when either is unknown. */
    fun zoomRatio(focal: Float, reference: Float?): Float? =
        if (focal > 0f && reference != null && reference > 0f) focal / reference else null

    /**
     * Fills [CameraLensInfo.zoomRatio] for every [LensLabelKind.ZOOM] lens
     * from the inventory's own 1x reference. Non-zoom lenses keep a null
     * ratio. Call on the deduplicated list before [disambiguate], which
     * compares rendered labels (ratios included).
     */
    fun assignZoomRatios(lenses: List<CameraLensInfo>): List<CameraLensInfo> {
        val reference = referenceWideFocal(lenses)
        return lenses.map { lens ->
            if (lens.labelKind == LensLabelKind.ZOOM) {
                lens.copy(zoomRatio = zoomRatio(lens.focalLength, reference))
            } else {
                lens
            }
        }
    }

    /**
     * The English base label behind the API payloads and logs — the same
     * mapping the localized surfaces apply to [LensLabelKind], pinned to
     * English so a remote dashboard on a stale cache still renders something.
     * Zoom lenses render their relative magnification ("2x"); with an unknown
     * ratio (no 1x reference) they fall back to the focal length so the chip
     * is never blank.
     */
    fun baseLabel(
        lensFacing: Int,
        kind: LensLabelKind,
        focalLength: Float,
        cameraId: String,
        zoomRatio: Float? = null,
    ): String {
        val front = lensFacing == CameraSelector.LENS_FACING_FRONT
        return when (kind) {
            LensLabelKind.FRONT -> "Front"
            LensLabelKind.BACK -> "Back"
            LensLabelKind.ULTRAWIDE -> if (front) "Front Ultrawide" else "Ultrawide"
            LensLabelKind.WIDE -> "Wide"
            LensLabelKind.ZOOM -> {
                val zoom = if (zoomRatio != null && zoomRatio > 0f) {
                    formatZoom(zoomRatio)
                } else {
                    formatFocal(focalLength)
                }
                if (front) "Front $zoom" else zoom
            }
            LensLabelKind.FOCAL_MM -> if (front) "Front ${formatFocal(focalLength)}" else formatFocal(focalLength)
            LensLabelKind.CAMERA_ID -> "Camera $cameraId"
        }
    }

    /** [CameraLensInfo] form of [baseLabel] — prefer this at call sites so the five fields travel as one. */
    fun baseLabel(lens: CameraLensInfo): String =
        baseLabel(lens.lensFacing, lens.labelKind, lens.focalLength, lens.id, lens.zoomRatio)

    /** "24" + mm for whole focals, one trimmed decimal otherwise ("3.9mm"). */
    fun formatFocal(focalLength: Float, locale: Locale = Locale.US): String =
        formatTrimmedOneDecimal(focalLength, "mm", locale)

    /**
     * "2x" for whole magnifications, one trimmed decimal otherwise ("2.3x") —
     * locale-aware like [formatFocal], so German renders "2,3x". The "x"
     * suffix stays untranslated (identical in every locale catalog).
     */
    fun formatZoom(ratio: Float, locale: Locale = Locale.US): String =
        formatTrimmedOneDecimal(ratio, "x", locale)

    private fun formatTrimmedOneDecimal(value: Float, suffix: String, locale: Locale): String {
        val oneDecimal = Math.round(value * 10f) / 10f
        val text = if (oneDecimal % 1f == 0f) {
            String.format(locale, "%d", oneDecimal.toInt())
        } else {
            String.format(locale, "%.1f", oneDecimal)
        }
        return "$text$suffix"
    }

    /**
     * Same-label collision pass: within one facing, a physical lens that
     * would render the same English base label as an entry before it (the
     * OEM's logical camera plus a same-band physical, the "Front / Front"
     * chips) drops to the focal band, so the two chips stay distinguishable.
     * Comparison is on the rendered label — two zooms with different ratios
     * ("2x" vs "3x") never collide. Logical cameras — the main binding
     * targets — always keep their band label.
     */
    fun disambiguate(lenses: List<CameraLensInfo>): List<CameraLensInfo> {
        val seen = mutableSetOf<Pair<Int, String>>()
        return lenses.map { lens ->
            val rendered = baseLabel(lens)
            val key = lens.lensFacing to rendered
            val collides = lens.physicalCameraId != null && key in seen
            if (!collides) seen.add(key)
            if (collides) lens.copy(labelKind = LensLabelKind.FOCAL_MM, zoomRatio = null) else lens
        }
    }

    /** OEMs often duplicate lenses sharing facing and focal length. */
    fun deduplicate(lenses: List<CameraLensInfo>): List<CameraLensInfo> =
        lenses.distinctBy { Pair(it.lensFacing, it.focalLength) }

    /** Back cameras by ascending focal length, then front cameras. */
    fun sortLenses(lenses: List<CameraLensInfo>): List<CameraLensInfo> =
        lenses.sortedWith(
            compareBy<CameraLensInfo> { it.lensFacing != CameraSelector.LENS_FACING_BACK }
                .thenBy { it.focalLength }
        )

    /**
     * The main logical back camera: the first back entry. Direct binding to
     * physical cameras on start causes black screens on many OEM drivers,
     * so the default must stay logical. 0 when there is no back lens —
     * callers fall back to the first entry, as before.
     */
    fun defaultBackIndex(sorted: List<CameraLensInfo>): Int =
        sorted.indexOfFirst { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            .coerceAtLeast(0)

    /**
     * The next lens in the camera-switch cycle: one forward, wrapping to 0
     * after the last entry. [size] must be non-empty — callers consult
     * [fallbackSelector] first when the inventory is empty.
     */
    fun nextIndex(currentIndex: Int, size: Int): Int = (currentIndex + 1) % size

    /**
     * The empty-inventory fallback: a plain front/back toggle — currently
     * front flips to the default back camera, anything else to the default
     * front camera. Returns the selector to bind and the facing it
     * represents.
     */
    fun fallbackSelector(currentFront: Boolean): Pair<CameraSelector, Boolean> =
        if (currentFront) {
            CameraSelector.DEFAULT_BACK_CAMERA to false
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA to true
        }

    fun fallbackLenses(): List<CameraLensInfo> = listOf(
        CameraLensInfo(
            id = "0",
            labelKind = LensLabelKind.BACK,
            lensFacing = CameraSelector.LENS_FACING_BACK,
            focalLength = 0f,
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
        ),
        CameraLensInfo(
            id = "1",
            labelKind = LensLabelKind.FRONT,
            lensFacing = CameraSelector.LENS_FACING_FRONT,
            focalLength = 0f,
            cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA,
        )
    )
}
