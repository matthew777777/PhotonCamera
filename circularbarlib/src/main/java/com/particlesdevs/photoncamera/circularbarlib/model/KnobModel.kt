package com.particlesdevs.photoncamera.circularbarlib.model

import com.particlesdevs.photoncamera.circularbarlib.control.models.ManualModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Modernized data class responsible for the behavior and appearance of KnobView
 * using StateFlow for observation.
 */
class KnobModel {
    enum class KnobModelFields {
        MANUAL_MODEL, VISIBILITY, RESET
    }

    private val _manualModelFlow = MutableStateFlow<ManualModel<*>?>(null)
    val manualModelFlow = _manualModelFlow.asStateFlow()
    var manualModel: ManualModel<*>?
        get() = _manualModelFlow.value
        set(value) { _manualModelFlow.value = value }

    private val _knobResetCalledFlow = MutableStateFlow(false)
    val knobResetCalledFlow = _knobResetCalledFlow.asStateFlow()
    var isKnobResetCalled: Boolean
        get() = _knobResetCalledFlow.value
        set(value) { _knobResetCalledFlow.value = value }

    private val _knobVisibleFlow = MutableStateFlow(false)
    val knobVisibleFlow = _knobVisibleFlow.asStateFlow()
    var isKnobVisible: Boolean
        get() = _knobVisibleFlow.value
        set(value) { _knobVisibleFlow.value = value }
}
