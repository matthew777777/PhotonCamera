package com.particlesdevs.photoncamera.gallery.compare

import android.graphics.PointF
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Modernized data class which stores current zoom and pan state
 * using StateFlow for observation.
 */
class ScaleAndPan {
    data class State(
        val scale: Float = 0f,
        val center: PointF? = null,
        val origin: Int = 0
    )

    private val _stateFlow = MutableStateFlow(State())
    val stateFlow = _stateFlow.asStateFlow()

    var scale: Float
        get() = _stateFlow.value.scale
        set(value) {
            _stateFlow.value = _stateFlow.value.copy(scale = value)
        }

    var center: PointF?
        get() = _stateFlow.value.center
        set(value) {
            _stateFlow.value = _stateFlow.value.copy(center = value)
        }

    var origin: Int
        get() = _stateFlow.value.origin
        set(value) {
            _stateFlow.value = _stateFlow.value.copy(origin = value)
        }
}
