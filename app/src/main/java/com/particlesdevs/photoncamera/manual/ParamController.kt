package com.particlesdevs.photoncamera.manual

import android.hardware.camera2.CaptureRequest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.particlesdevs.photoncamera.capture.CaptureController
import com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel
import com.particlesdevs.photoncamera.processing.parameters.ExposureIndex
import com.particlesdevs.photoncamera.settings.PreferenceKeys
import com.particlesdevs.photoncamera.util.Log
import kotlinx.coroutines.launch

import com.particlesdevs.photoncamera.capture.PreviewManager

/**
 * Controller class responsible for setting manual mode parameters to the camera preview.
 * Modernized to use Kotlin Coroutines and StateFlow.
 */
class ParamController(
    private val captureController: CaptureController,
    private val previewManager: PreviewManager
) {
    @JvmField var ISO: Int = -1
    @JvmField var EV: Int = 0
    @JvmField var SHUTTER: Long = -1L
    @JvmField var FOCUS: Float = -1f
    @JvmField var WB: Double = ManualParamModel.WB_AUTO
    
    private val TAG = "ParamController"
    private var manualParamModel: ManualParamModel? = null

    fun observeModel(lifecycleOwner: LifecycleOwner, model: ManualParamModel) {
        this.manualParamModel = model
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    model.isoFlow.collect { isoVal ->
                        ISO = isoVal.toInt()
                        setISO(ISO, model.currentExposureValue)
                    }
                }
                launch {
                    model.evFlow.collect { evVal ->
                        EV = evVal.toInt()
                        setEV(EV)
                    }
                }
                launch {
                    model.exposureFlow.collect { shutterVal ->
                        SHUTTER = shutterVal.toLong()
                        setShutter(SHUTTER, model.currentISOValue.toInt())
                    }
                }
                launch {
                    model.focusFlow.collect { focusVal ->
                        FOCUS = focusVal.toFloat()
                        setFocus(FOCUS)
                    }
                }
                launch {
                    model.wbFlow.collect { wbVal ->
                        WB = wbVal
                        setWB(WB)
                    }
                }
                launch {
                    model.panelInvisibilityEvent.collect {
                        Log.d(TAG, "update: " + model.isManualMode())
                        ISO = -1
                        EV = 0
                        SHUTTER = -1L
                        FOCUS = -1f
                        captureController.unlockFocus()
                    }
                }
            }
        }
    }

    fun setShutter(shutterNs: Long, currentISO: Int) {
        val builder = captureController.mPreviewRequestBuilder
        if (builder == null) {
            Log.w(TAG, "setShutter(): mPreviewRequestBuilder is null")
            return
        }
        if (shutterNs == ManualParamModel.EXPOSURE_AUTO.toLong()) {
            if (currentISO == ManualParamModel.ISO_AUTO.toInt()) {
                captureController.resetPreviewAEMode()
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, Math.min(shutterNs, ExposureIndex.sec / 5))
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, captureController.mPreviewIso)
        }
        captureController.rebuildPreviewBuilder()
    }

    fun setISO(isoVal: Int, currentExposure: Double) {
        val builder = captureController.mPreviewRequestBuilder
        if (builder == null) {
            Log.w(TAG, "setISO(): mPreviewRequestBuilder is null")
            return
        }
        if (isoVal == ManualParamModel.ISO_AUTO.toInt()) {
            if (currentExposure == ManualParamModel.EXPOSURE_AUTO) {
                captureController.resetPreviewAEMode()
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, isoVal)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, captureController.mPreviewExposureTime)
        }
        captureController.rebuildPreviewBuilder()
    }

    fun setFocus(focusDist: Float) {
        val builder = captureController.mPreviewRequestBuilder
        if (builder == null) {
            Log.w(TAG, "setFocus(): mPreviewRequestBuilder is null")
            return
        }
        if (focusDist == ManualParamModel.FOCUS_AUTO.toFloat()) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, PreferenceKeys.getAfMode())
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDist)
        }
        captureController.rebuildPreviewBuilder()
    }

    fun setEV(ev: Int) {
        val builder = captureController.mPreviewRequestBuilder
        if (builder == null) {
            Log.w(TAG, "setEV(): mPreviewRequestBuilder is null")
            return
        }
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)
        captureController.rebuildPreviewBuilder()
    }

    fun setWB(wbValue: Double) {
        val builder = captureController.mPreviewRequestBuilder
        if (builder == null) {
            Log.w(TAG, "setWB(): mPreviewRequestBuilder is null")
            return
        }
        when (wbValue) {
            ManualParamModel.WB_AUTO -> builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            ManualParamModel.WB_INCANDESCENT -> builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT)
            ManualParamModel.WB_FLUORESCENT -> builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT)
            ManualParamModel.WB_WARM_FLUORESCENT -> builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT)
            ManualParamModel.WB_DAYLIGHT -> builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT)
            ManualParamModel.WB_CLOUDY -> builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT)
            ManualParamModel.WB_TWILIGHT -> builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_TWILIGHT)
            ManualParamModel.WB_SHADE -> builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_SHADE)
        }
        captureController.rebuildPreviewBuilder()
    }

    fun isManualMode(): Boolean {
        return manualParamModel?.isManualMode() ?: false
    }

    fun setupPreview() {
        val model = manualParamModel ?: return
        if (ISO != -1) setISO(ISO, model.currentExposureValue)
        if (EV != 0) setEV(EV)
        if (SHUTTER != -1L) setShutter(SHUTTER, ISO)
        if (FOCUS != -1f) setFocus(FOCUS)
    }

    fun getCurrentExposureValue(): Double {
        return manualParamModel?.currentExposureValue ?: ManualParamModel.EXPOSURE_AUTO
    }

    fun getCurrentISOValue(): Double {
        return manualParamModel?.currentISOValue ?: ManualParamModel.ISO_AUTO
    }
}
