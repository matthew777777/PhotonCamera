package com.particlesdevs.photoncamera.ui.camera.views;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatButton;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

public class FlashButton extends AppCompatButton {
    private static final int[] STATE_FLASH_OFF = {R.attr.flash_off};
    private static final int[] STATE_FLASH_TORCH = {R.attr.flash_torch};
    private boolean flash_off;
    private boolean flash_torch;

    public FlashButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        // In the layout editor PreferenceKeys is not initialised yet.
        if (!isInEditMode()) {
            setFlashValueState(PreferenceKeys.getAeMode());
        }
    }

    @Override
    protected int[] onCreateDrawableState(int extraSpace) {
        final int[] drawableState = super.onCreateDrawableState(extraSpace + 2);
        if (flash_off)
            mergeDrawableStates(drawableState, STATE_FLASH_OFF);
        if (flash_torch)
            mergeDrawableStates(drawableState, STATE_FLASH_TORCH);
        return drawableState;
    }

    public void setFlashValueState(int flashmode) {
        flash_off = false;
        flash_torch = false;
        switch (flashmode) {
            case 0:
                flash_torch = true;
                break;
            default:
                flash_off = true;
                break;
        }
        refreshDrawableState();
    }
}
