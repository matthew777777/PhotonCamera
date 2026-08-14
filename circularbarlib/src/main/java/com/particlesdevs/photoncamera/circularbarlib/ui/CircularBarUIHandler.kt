package com.particlesdevs.photoncamera.circularbarlib.ui

import android.app.Activity
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.particlesdevs.photoncamera.circularbarlib.R
import com.particlesdevs.photoncamera.circularbarlib.model.KnobModel
import com.particlesdevs.photoncamera.circularbarlib.model.ManualModeModel
import com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Modernized UI handler for the circular bar, replacing ViewObserver.
 * Uses StateFlow collection within lifecycle-aware scopes.
 */
class CircularBarUIHandler(
    private val activity: Activity,
    private val knobModel: KnobModel,
    private val manualModeModel: ManualModeModel
) {
    private val manualMode: RelativeLayout = activity.findViewById(R.id.manual_mode)
    private val knobView: KnobView = activity.findViewById(R.id.knobView)
    private val isoOption: TextView = activity.findViewById(R.id.iso_option_tv)
    private val expOption: TextView = activity.findViewById(R.id.exposure_option_tv)
    private val evOption: TextView = activity.findViewById(R.id.ev_option_tv)
    private val focusOption: TextView = activity.findViewById(R.id.focus_option_tv)
    private val wbOption: TextView = activity.findViewById(R.id.wb_option_tv)
    private val buttonsContainer: LinearLayout = activity.findViewById(R.id.buttons_container)
    
    private val textViews = listOf(isoOption, evOption, expOption, focusOption, wbOption)
    
    private var rotation = 0

    private val orientationEventListener = object : OrientationEventListener(activity.baseContext) {
        private val ROT_DUR = 350L
        private var prevOrientation = ORIENTATION_UNKNOWN

        override fun onOrientationChanged(orientation: Int) {
            if (Settings.System.getInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 0)
                return
            
            var currentOrientation = ORIENTATION_UNKNOWN
            if (orientation >= 340 || orientation < 20 && rotation != 0) {
                currentOrientation = Surface.ROTATION_0
                rotation = 0
            } else if (orientation >= 70 && orientation < 110 && rotation != 90) {
                currentOrientation = Surface.ROTATION_270
                rotation = -90
            } else if (orientation >= 160 && orientation < 200 && rotation != 180) {
                currentOrientation = Surface.ROTATION_180
                rotation = 180
            } else if (orientation >= 250 && orientation < 290 && rotation != 270) {
                currentOrientation = Surface.ROTATION_90
                rotation = 90
            }
            
            if (prevOrientation != currentOrientation && orientation != ORIENTATION_UNKNOWN) {
                prevOrientation = currentOrientation
                if (currentOrientation != ORIENTATION_UNKNOWN) {
                    Binding.rotateKnobView(knobView, rotation)
                    Binding.rotateViewGroupChild(buttonsContainer, rotation, ROT_DUR)
                }
            }
        }
    }

    init {
        (activity as? LifecycleOwner)?.lifecycleScope?.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                observeKnobModel()
                observeManualModeModel()
            }
        }
    }

    private fun CoroutineScope.observeKnobModel() {
        launch {
            knobModel.manualModelFlow.collect { model ->
                Binding.setModelToKnob(knobView, model)
            }
        }
        launch {
            knobModel.knobVisibleFlow.collect { visible ->
                Binding.setKnobVisibility(knobView, visible)
            }
        }
        launch {
            knobModel.knobResetCalledFlow.collect { reset ->
                Binding.resetKnob(knobView, reset)
            }
        }
    }

    private fun CoroutineScope.observeManualModeModel() {
        launch { manualModeModel.evTextFlow.collect { evOption.text = it } }
        launch { manualModeModel.exposureTextFlow.collect { expOption.text = it } }
        launch { manualModeModel.isoTextFlow.collect { isoOption.text = it } }
        launch { manualModeModel.focusTextFlow.collect { focusOption.text = it } }
        launch { manualModeModel.wbTextFlow.collect { wbOption.text = it } }
        
        launch { manualModeModel.evTextClickedFlow.collect { evOption.setOnClickListener(it) } }
        launch { manualModeModel.exposureTextClickedFlow.collect { expOption.setOnClickListener(it) } }
        launch { manualModeModel.isoTextClickedFlow.collect { isoOption.setOnClickListener(it) } }
        launch { manualModeModel.focusTextClickedFlow.collect { focusOption.setOnClickListener(it) } }
        launch { manualModeModel.wbTextClickedFlow.collect { wbOption.setOnClickListener(it) } }
        
        launch {
            manualModeModel.selectedTextViewIdFlow.collect { id ->
                val v = activity.findViewById<View>(id)
                textViews.forEach { it.isSelected = (it == v) }
            }
        }
        
        launch {
            manualModeModel.manualPanelVisibleFlow.collect { visible ->
                Binding.togglePanelVisibility(manualMode, visible)
            }
        }
    }

    fun enableOrientationListener() {
        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable()
        }
    }

    fun disableOrientationListener() {
        orientationEventListener.disable()
    }
}
