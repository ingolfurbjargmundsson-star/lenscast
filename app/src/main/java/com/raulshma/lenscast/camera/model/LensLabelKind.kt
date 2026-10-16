package com.raulshma.lenscast.camera.model

/**
 * The display role of a lens — the data half of its chip label. Pure banding,
 * no strings: the app screen and the web dashboard build localized labels
 * from it (the reviewer-visible English chips were fixed strings before);
 * [LensInventory.baseLabel] keeps an English base for the API payloads and logs.
 *
 * Telephoto lenses share one [ZOOM] kind — the magnification (e.g. 2x, 3.4x)
 * rides [CameraLensInfo.zoomRatio] relative to the main wide lens, so a 4x or
 * 10x periscope never needs a new enum value or new strings.
 */
enum class LensLabelKind {
    /** The main front camera — or any front whose focal is unknown or wide-band. */
    FRONT,

    /** The generic fallback entry when enumeration fails entirely. */
    BACK,

    ULTRAWIDE,

    WIDE,

    /**
     * Any telephoto lens (focal at/above the wide band). The chip shows the
     * relative magnification from [CameraLensInfo.zoomRatio] ("2x"), falling
     * back to the focal length when the reference is unknown.
     */
    ZOOM,

    /**
     * A focal with no friendlier band — and the demotion target when a
     * physical lens would render the same label as its logical sibling
     * (an OEM's wide + wide, say), so two chips never read identically.
     */
    FOCAL_MM,

    /** A back lens whose focal is unknown — labeled by camera id instead. */
    CAMERA_ID,
}
