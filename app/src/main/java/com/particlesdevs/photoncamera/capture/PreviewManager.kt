package com.particlesdevs.photoncamera.capture

import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.particlesdevs.photoncamera.settings.PreferenceKeys
import com.particlesdevs.photoncamera.ui.camera.views.viewfinder.GLPreview
import com.particlesdevs.photoncamera.util.log.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreviewManager @Inject constructor(
    private val lifecycleManager: CameraLifecycleManager
) {
    companion object {
        const val STATE_PREVIEW = 0
        const val STATE_WAITING_LOCK = 1
        const val STATE_WAITING_PRECAPTURE = 2
        const val STATE_WAITING_NON_PRECAPTURE = 3
        const val STATE_PICTURE_TAKEN = 4
        const val STATE_CLOSED = 5
    }
    
    private val TAG = "PreviewManager"

    var previewRequestBuilder: CaptureRequest.Builder? = null
    var previewInputRequest: CaptureRequest? = null
    var captureSession: CameraCaptureSession? = null
    
    var previewSize: Size? = null
    var bufferSize: Size? = null
    
    var imageReaderPreview: ImageReader? = null
    var imageReaderRaw: ImageReader? = null
    var previewSurface: Surface? = null

    var state: Int = STATE_PREVIEW
    var sensorOrientation: Int = 0

    var previewCaptureResult: CaptureResult? = null
    var previewCaptureRequest: CaptureRequest? = null

    var previewMeteringAE: Array<MeteringRectangle>? = null

    fun rebuildPreviewBuilder(captureCallback: CameraCaptureSession.CaptureCallback, burst: Boolean) {
        if (burst) return
        try {
            val session = captureSession
            val builder = previewRequestBuilder
            if (session != null && builder != null) {
                previewInputRequest = builder.build()
                session.setRepeatingRequest(previewInputRequest!!, captureCallback, lifecycleManager.backgroundHandler)
            }
        } catch (e: Exception) {
            Logger.warnShort(TAG, "Cannot rebuildPreviewBuilder()!", e)
        }
    }

    fun rebuildPreviewBuilderOneShot(captureCallback: CameraCaptureSession.CaptureCallback, burst: Boolean) {
        if (burst) return
        try {
            val session = captureSession
            val builder = previewRequestBuilder
            if (session != null && builder != null) {
                session.capture(builder.build(), captureCallback, lifecycleManager.backgroundHandler)
            }
        } catch (e: Exception) {
            Logger.warnShort(TAG, "Cannot rebuildPreviewBuilderOneShot()!", e)
        }
    }

    fun lockFocus(burst: Boolean, captureCallback: CameraCaptureSession.CaptureCallback) {
        if (burst) return
        val session = captureSession
        val builder = previewRequestBuilder
        if (builder == null || session == null) {
            Log.w(TAG, "lockFocus(): camera not ready")
            return
        }

        previewCaptureResult?.let { result ->
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            if (afState != null && (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) {
                state = STATE_WAITING_LOCK
                return
            }
        }

        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        state = STATE_WAITING_LOCK
        try {
            session.setRepeatingRequest(builder.build(), captureCallback, lifecycleManager.backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera preview", e)
        }
    }

    fun unlockFocus(burst: Boolean, captureCallback: CameraCaptureSession.CaptureCallback) {
        if (burst) return
        try {
            val session = captureSession
            val builder = previewRequestBuilder
            if (builder != null && session != null) {
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                session.capture(builder.build(), captureCallback, lifecycleManager.backgroundHandler)
                state = STATE_PREVIEW
                session.setRepeatingRequest(builder.build(), captureCallback, lifecycleManager.backgroundHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "unlockFocus error: ", e)
        }
    }

    fun runPreCaptureSequence(burst: Boolean, captureCallback: CameraCaptureSession.CaptureCallback) {
        if (burst) return
        try {
            val session = captureSession
            val builder = previewRequestBuilder
            if (session != null && builder != null) {
                builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                state = STATE_WAITING_PRECAPTURE
                session.capture(builder.build(), captureCallback, lifecycleManager.backgroundHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "runPreCaptureSequence error: ", e)
        }
    }

    fun applyAeMetering(characteristics: CameraCharacteristics, initialMeteringAE: Array<MeteringRectangle>?) {
        val builder = previewRequestBuilder ?: return
        val mode = PreferenceKeys.getAeMeteringStd()
        var rectangles = getAEMeteringRectangles(characteristics, mode)
        if (mode == -1) {
            rectangles = initialMeteringAE
        }
        val maxAeRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        if (maxAeRegions > 0) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, rectangles)
            previewMeteringAE = rectangles
        }
    }

    private fun getAEMeteringRectangles(characteristics: CameraCharacteristics, mode: Int): Array<MeteringRectangle>? {
        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val width = activeArray.width()
        val height = activeArray.height()

        return when (mode) {
            0 -> { // Center Weighted
                val maxRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
                if (maxRegions >= 3) {
                    arrayOf(
                        MeteringRectangle(width / 4, height / 4, width / 2, height / 2, 500),
                        MeteringRectangle(width / 3, height / 3, width / 3, height / 3, 800),
                        MeteringRectangle(width * 3 / 8, height * 3 / 8, width / 4, height / 4, 1000)
                    )
                } else {
                    arrayOf(MeteringRectangle(width / 4, height / 4, width / 2, height / 2, 1000))
                }
            }
            1 -> { // Spot
                arrayOf(MeteringRectangle(width / 2 - 100, height / 2 - 100, 200, 200, 1000))
            }
            2 -> { // Matrix/Frame
                arrayOf(MeteringRectangle(0, 0, width, height, 1000))
            }
            else -> null
        }
    }

    fun close() {
        captureSession?.close()
        captureSession = null
        previewRequestBuilder = null
        previewInputRequest = null
    }
}
