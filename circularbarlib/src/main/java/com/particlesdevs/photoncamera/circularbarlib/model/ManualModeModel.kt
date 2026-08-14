package com.particlesdevs.photoncamera.circularbarlib.model

import android.view.View
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Modernized data class responsible for the behavior and appearance of manual_mode layout
 * using StateFlow for observation.
 */
class ManualModeModel {
    enum class ManualModelFields {
        FOCUS_TEXT, EXP_TEXT, ISO_TEXT, EV_TEXT, WB_TEXT, PANEL_VISIBILITY, SELECTED_TV, 
        FOCUS_LISTENER, EXP_LISTENER, EV_LISTENER, ISO_LISTENER, WB_LISTENER
    }

    private val _focusText = MutableStateFlow("")
    val focusTextFlow = _focusText.asStateFlow()
    var focusText: String
        get() = _focusText.value
        set(value) { _focusText.value = value }

    private val _exposureText = MutableStateFlow("")
    val exposureTextFlow = _exposureText.asStateFlow()
    var exposureText: String
        get() = _exposureText.value
        set(value) { _exposureText.value = value }

    private val _isoText = MutableStateFlow("")
    val isoTextFlow = _isoText.asStateFlow()
    var isoText: String
        get() = _isoText.value
        set(value) { _isoText.value = value }

    private val _evText = MutableStateFlow("")
    val evTextFlow = _evText.asStateFlow()
    var evText: String
        get() = _evText.value
        set(value) { _evText.value = value }

    private val _wbText = MutableStateFlow("")
    val wbTextFlow = _wbText.asStateFlow()
    var wbText: String
        get() = _wbText.value
        set(value) { _wbText.value = value }

    private val _focusTextClicked = MutableStateFlow<View.OnClickListener?>(null)
    val focusTextClickedFlow = _focusTextClicked.asStateFlow()
    var focusTextClicked: View.OnClickListener?
        get() = _focusTextClicked.value
        set(value) { _focusTextClicked.value = value }

    private val _exposureTextClicked = MutableStateFlow<View.OnClickListener?>(null)
    val exposureTextClickedFlow = _exposureTextClicked.asStateFlow()
    var exposureTextClicked: View.OnClickListener?
        get() = _exposureTextClicked.value
        set(value) { _exposureTextClicked.value = value }

    private val _evTextClicked = MutableStateFlow<View.OnClickListener?>(null)
    val evTextClickedFlow = _evTextClicked.asStateFlow()
    var evTextClicked: View.OnClickListener?
        get() = _evTextClicked.value
        set(value) { _evTextClicked.value = value }

    private val _isoTextClicked = MutableStateFlow<View.OnClickListener?>(null)
    val isoTextClickedFlow = _isoTextClicked.asStateFlow()
    var isoTextClicked: View.OnClickListener?
        get() = _isoTextClicked.value
        set(value) { _isoTextClicked.value = value }

    private val _wbTextClicked = MutableStateFlow<View.OnClickListener?>(null)
    val wbTextClickedFlow = _wbTextClicked.asStateFlow()
    var wbTextClicked: View.OnClickListener?
        get() = _wbTextClicked.value
        set(value) { _wbTextClicked.value = value }

    private val _manualPanelVisible = MutableStateFlow(false)
    val manualPanelVisibleFlow = _manualPanelVisible.asStateFlow()
    var isManualPanelVisible: Boolean
        get() = _manualPanelVisible.value
        set(value) { _manualPanelVisible.value = value }

    private val _selectedTextViewId = MutableStateFlow(-1)
    val selectedTextViewIdFlow = _selectedTextViewId.asStateFlow()
    var selectedTextViewId: Int
        get() = _selectedTextViewId.value
        set(value) { _selectedTextViewId.value = value }
        
    // Bridge for Java/DataBinding if needed
    fun setCheckedTextViewId(id: Int) {
        selectedTextViewId = id
    }
}
