package com.particlesdevs.photoncamera.circularbarlib.control

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Modernized data class which stores the manual shutter, focus, iso and ev values
 * using StateFlow for observation.
 */
class ManualParamModel {
    companion object {
        const val EXPOSURE_AUTO = 0.0
        const val EV_AUTO = 0.0
        const val ISO_AUTO = 0.0
        const val FOCUS_AUTO = -1.0
        const val WB_AUTO = -1.0
        const val WB_INCANDESCENT = -2.0
        const val WB_FLUORESCENT = -3.0
        const val WB_WARM_FLUORESCENT = -4.0
        const val WB_DAYLIGHT = -5.0
        const val WB_CLOUDY = -6.0
        const val WB_TWILIGHT = -7.0
        const val WB_SHADE = -8.0

        const val ID_FOCUS = "focus"
        const val ID_EV = "ev"
        const val ID_SHUTTER = "shutter"
        const val ID_ISO = "iso"
        const val ID_WB = "wb"
        const val PANEL_INVISIBILITY = "panel_invisibility"
    }

    private val _focusFlow = MutableStateFlow(FOCUS_AUTO)
    val focusFlow = _focusFlow.asStateFlow()
    var currentFocusValue: Double
        get() = _focusFlow.value
        set(value) { _focusFlow.value = value }

    private val _evFlow = MutableStateFlow(EV_AUTO)
    val evFlow = _evFlow.asStateFlow()
    var currentEvValue: Double
        get() = _evFlow.value
        set(value) { _evFlow.value = value }

    private val _exposureFlow = MutableStateFlow(EXPOSURE_AUTO)
    val exposureFlow = _exposureFlow.asStateFlow()
    var currentExposureValue: Double
        get() = _exposureFlow.value
        set(value) { _exposureFlow.value = value }

    private val _isoFlow = MutableStateFlow(ISO_AUTO)
    val isoFlow = _isoFlow.asStateFlow()
    var currentISOValue: Double
        get() = _isoFlow.value
        set(value) { _isoFlow.value = value }

    private val _wbFlow = MutableStateFlow(WB_AUTO)
    val wbFlow = _wbFlow.asStateFlow()
    var currentWbValue: Double
        get() = _wbFlow.value
        set(value) { _wbFlow.value = value }

    private val _panelEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val panelInvisibilityEvent = _panelEvent.asSharedFlow()

    fun isManualMode(): Boolean {
        return !(currentExposureValue == EXPOSURE_AUTO &&
                currentFocusValue == FOCUS_AUTO &&
                currentISOValue == ISO_AUTO &&
                currentEvValue == EV_AUTO &&
                currentWbValue == WB_AUTO)
    }

    fun reset() {
        currentFocusValue = FOCUS_AUTO
        currentEvValue = EV_AUTO
        currentExposureValue = EXPOSURE_AUTO
        currentISOValue = ISO_AUTO
        currentWbValue = WB_AUTO
        _panelEvent.tryEmit(Unit)
    }
}
