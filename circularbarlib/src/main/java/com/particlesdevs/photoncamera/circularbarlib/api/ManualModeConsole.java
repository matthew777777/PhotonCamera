package com.particlesdevs.photoncamera.circularbarlib.api;

import android.app.Activity;
import android.hardware.camera2.CameraCharacteristics;

import com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel;

public interface ManualModeConsole {

    void init(Activity activity, CameraCharacteristics cameraCharacteristics);

    void onResume();

    void onPause();

    void onDestroy();

    ManualParamModel getManualParamModel();

    void setPanelVisibility(boolean visible);

    void resetAllValues();

    boolean isManualMode();

    boolean isPanelVisible();

    void retractAllKnobs();

    boolean isFocusParameterSelected();

    boolean isManualFocusModeActive();
}
