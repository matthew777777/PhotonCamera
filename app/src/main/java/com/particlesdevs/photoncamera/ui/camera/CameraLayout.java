package com.particlesdevs.photoncamera.ui.camera;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

/**
 * Root view of {@code camera_fragment.xml}.
 * <p>
 * The Android Studio layout editor cannot run fragments, viewmodels or the
 * data-binding pipeline, so the camera UI would be laid out with un-evaluated
 * bindings. On inflation for a preview this view asks {@link CameraFragment}
 * to apply its default configuration, reusing the exact binding adapters that
 * run at runtime (no duplicated layout code).
 */
public class CameraLayout extends ConstraintLayout {

    public CameraLayout(@NonNull Context context) {
        super(context);
    }

    public CameraLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public CameraLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (isInEditMode()) {
            CameraFragment.preparePreviewLayout(this);
        }
    }
}
