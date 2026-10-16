package com.raulshma.lenscast.camera.model

import androidx.camera.core.CameraSelector

/**
 * Represents a selectable camera lens on the device.
 *
 * @param id Unique identifier for this camera (Camera2 camera ID)
 * @param labelKind Display role of the lens (e.g., [LensLabelKind.WIDE],
 *   [LensLabelKind.FRONT]) — surfaces build localized labels from it
 * @param lensFacing CameraSelector lens facing constant
 * @param focalLength Focal length in mm (used for sorting / labeling)
 * @param cameraSelector CameraSelector that targets this specific camera
 * @param physicalCameraId Optional physical camera ID if this isn't the primary logical camera
 * @param zoomRatio Magnification relative to the main wide lens (focal /
 *   reference), set for [LensLabelKind.ZOOM] by
 *   [LensInventory.assignZoomRatios]; null when not a zoom lens or when the
 *   reference is unknown (surfaces fall back to the focal length).
 */
data class CameraLensInfo(
    val id: String,
    val labelKind: LensLabelKind,
    val lensFacing: Int,
    val focalLength: Float,
    val cameraSelector: CameraSelector,
    val physicalCameraId: String? = null,
    val zoomRatio: Float? = null,
)
