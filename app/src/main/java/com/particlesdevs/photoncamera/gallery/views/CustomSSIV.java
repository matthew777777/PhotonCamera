package com.particlesdevs.photoncamera.gallery.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;

import static com.particlesdevs.photoncamera.gallery.helper.Constants.DOUBLE_TAP_ZOOM_DURATION_MS;

public class CustomSSIV extends SubsamplingScaleImageView {

    private TouchCallBack touchCallBack;

    public CustomSSIV(Context context) {
        super(context);
        setMinimumDpi(40);
        setOrientation(SubsamplingScaleImageView.ORIENTATION_USE_EXIF);
        setQuickScaleEnabled(true);
        setEagerLoadingEnabled(false);
        setDoubleTapZoomDuration(DOUBLE_TAP_ZOOM_DURATION_MS);
        setPreferredBitmapConfig(Bitmap.Config.ARGB_8888);
    }
    public CustomSSIV(Context context, AttributeSet attrs){
        super(context, attrs);
        setMinimumDpi(40);
        setOrientation(SubsamplingScaleImageView.ORIENTATION_USE_EXIF);
        setQuickScaleEnabled(true);
        setEagerLoadingEnabled(false);
        setDoubleTapZoomDuration(DOUBLE_TAP_ZOOM_DURATION_MS);
        setPreferredBitmapConfig(Bitmap.Config.ARGB_8888);
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (touchCallBack != null) {
            touchCallBack.onTouched(getId());
        }
        return super.onTouchEvent(event);
    }

    public void setTouchCallBack(TouchCallBack touchCallBack) {
        this.touchCallBack = touchCallBack;
    }

    public interface TouchCallBack {
        void onTouched(int id);
    }
}

