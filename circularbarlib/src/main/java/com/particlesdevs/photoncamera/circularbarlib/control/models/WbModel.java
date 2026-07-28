package com.particlesdevs.photoncamera.circularbarlib.control.models;

import android.content.Context;
import android.graphics.drawable.StateListDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Vibrator;
import android.util.Range;

import com.particlesdevs.photoncamera.circularbarlib.R;
import com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobInfo;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobItemInfo;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobView;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.ShadowTextDrawable;

/**
 * Created by vibhorSrv, eszdman
 */
public class WbModel extends ManualModel<Double> {

    public WbModel(Context context, CameraCharacteristics cameraCharacteristics, Range<Double> range,
                    ManualParamModel manualParamModel, ValueChangedEvent valueChangedEvent, Vibrator v) {
        super(context, cameraCharacteristics, range, manualParamModel, valueChangedEvent, v);
    }

    @Override
    protected void fillKnobInfoList() {
        // Auto
        KnobItemInfo auto = getNewAutoItem(ManualParamModel.WB_AUTO, null);
        getKnobInfoList().add(auto);
        currentInfo = auto;

        // Profiles
        addProfileItem(ManualParamModel.WB_DAYLIGHT, context.getString(R.string.wb_daylight));
        addProfileItem(ManualParamModel.WB_CLOUDY, context.getString(R.string.wb_cloudy));
        addProfileItem(ManualParamModel.WB_TUNGSTEN, context.getString(R.string.wb_tungsten));
        addProfileItem(ManualParamModel.WB_FLUORESCENT, context.getString(R.string.wb_fluorescent));

        int angle = context.getResources().getInteger(R.integer.manual_awb_knob_view_angle_half);
        knobInfo = new KnobInfo(0, angle, 0, getKnobInfoList().size() - 1, context.getResources().getInteger(R.integer.manual_awb_knob_view_auto_angle));
    }

    private void addProfileItem(double value, String label) {
        ShadowTextDrawable drawable = new ShadowTextDrawable();
        drawable.setTextAppearance(context, R.style.ManualModeKnobText);
        drawable.setText(label);
        ShadowTextDrawable drawableSelected = new ShadowTextDrawable();
        drawableSelected.setTextAppearance(context, R.style.ManualModeKnobTextSelected);
        drawableSelected.setText(label);

        StateListDrawable stateDrawable = new StateListDrawable();
        stateDrawable.addState(new int[]{-android.R.attr.state_selected}, drawable);
        stateDrawable.addState(new int[]{android.R.attr.state_selected}, drawableSelected);

        getKnobInfoList().add(new KnobItemInfo(stateDrawable, label, getKnobInfoList().size(), value));
    }

    @Override
    public void onRotationStateChanged(KnobView knobView, KnobView.RotationState rotationState) {
    }

    @Override
    public void onSelectedKnobItemChanged(KnobItemInfo knobItemInfo) {
        currentInfo = knobItemInfo;
        manualParamModel.setCurrentWbValue(knobItemInfo.value);
    }
}
