package com.particlesdevs.photoncamera.control;

import android.graphics.Point;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import com.particlesdevs.photoncamera.util.Log;
import android.util.Size;
import android.view.View;
import android.view.View.OnTouchListener;

import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.ui.camera.views.FocusCircleView;
import com.particlesdevs.photoncamera.ui.camera.views.viewfinder.GLPreview;

public class TouchFocus {
    private static final String TAG = "TouchFocus";
    private static final int AUTO_HIDE_DELAY_MS = 3000;
    private final CaptureController captureController;
    private final GLPreview textureView;
    private final View focusCircleView;
    private final View exposureCircleView;
    private final Runnable hideFocusCircleRunnable = this::hideFocusCircleView;
    public boolean isTouchFocus = false;

    private final OnTouchListener dragListener = new OnTouchListener() {
        private float startX, startY;
        private float initialX, initialY;

        @Override
        public boolean onTouch(View v, android.view.MotionEvent event) {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    startX = event.getRawX();
                    startY = event.getRawY();
                    initialX = v.getX();
                    initialY = v.getY();
                    focusCircleView.removeCallbacks(hideFocusCircleRunnable);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - startX;
                    float dy = event.getRawY() - startY;
                    v.setX(initialX + dx);
                    v.setY(initialY + dy);
                    updateRegionsFromCircles();
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    v.performClick();
                    focusCircleView.postDelayed(hideFocusCircleRunnable, AUTO_HIDE_DELAY_MS);
                    return true;
            }
            return false;
        }
    };


    public TouchFocus(CaptureController captureController, View focusCircle, View exposureCircle, GLPreview textureView) {
        this.captureController = captureController;
        this.focusCircleView = focusCircle;
        this.exposureCircleView = exposureCircle;
        this.textureView = textureView;
        focusCircleView.setOnTouchListener(dragListener);
        exposureCircleView.setOnTouchListener(dragListener);
        resetFocusCircle();
    }

    public void processTouchToFocus(float fx, float fy) {
        focusCircleView.removeCallbacks(hideFocusCircleRunnable);
        exposureCircleView.removeCallbacks(hideFocusCircleRunnable);

        focusCircleView.post(() -> {
            showCircle(focusCircleView, fx, fy);
            showCircle(exposureCircleView, fx, fy);
        });

        setFocus((int) fy, (int) fx);
        focusCircleView.postDelayed(hideFocusCircleRunnable, AUTO_HIDE_DELAY_MS);
    }

    private void showCircle(View view, float fx, float fy) {
        view.setX(fx - view.getMeasuredWidth() / 2.0f);
        view.setY(fy - view.getMeasuredHeight() / 2.0f);
        view.setVisibility(View.VISIBLE);
        view.setAlpha(1f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.animate().scaleY(1.2f).scaleX(1.2f).setDuration(250)
                .withEndAction(() -> view.animate().scaleY(1f).scaleX(1f).setDuration(250).start())
                .start();
    }

    private void updateRegionsFromCircles() {
        float afCenterX = focusCircleView.getX() + focusCircleView.getMeasuredWidth() / 2.0f;
        float afCenterY = focusCircleView.getY() + focusCircleView.getMeasuredHeight() / 2.0f;
        float aeCenterX = exposureCircleView.getX() + exposureCircleView.getMeasuredWidth() / 2.0f;
        float aeCenterY = exposureCircleView.getY() + exposureCircleView.getMeasuredHeight() / 2.0f;

        MeteringRectangle afRect = calculateMeteringRectangle((int) afCenterY, (int) afCenterX);
        MeteringRectangle aeRect = calculateMeteringRectangle((int) aeCenterY, (int) aeCenterX);

        if (afRect != null && aeRect != null) {
            triggerAutoFocus(new MeteringRectangle[]{afRect}, new MeteringRectangle[]{aeRect});
        }
    }

    /**
     * Sets state of focus circle view based on AF State
     */
    public void setState(Integer afstate) {
        if (afstate != null) {
            ((FocusCircleView) focusCircleView).setAfState(afstate);
        }
    }

    private void setFocus(int x, int y) {
        MeteringRectangle rect = calculateMeteringRectangle(x, y);
        if (rect != null) {
            MeteringRectangle[] rects = new MeteringRectangle[]{rect};
            triggerAutoFocus(rects, rects);
        }
    }

    private MeteringRectangle calculateMeteringRectangle(int x, int y) {
        if (captureController.mImageReaderPreview == null) {
            Log.w(TAG, "calculateMeteringRectangle(): mImageReaderPreview is null, camera not ready yet");
            return null;
        }
        Point size = new Point(captureController.mImageReaderPreview.getWidth(), captureController.mImageReaderPreview.getHeight());
        Point CurUi = new Point(textureView.getWidth(), textureView.getHeight());
        Size sizee = CaptureController.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        if (sizee == null) {
            sizee = new Size(size.x, size.y);
        }
        if (x < 0)
            x = 0;
        if (y < 0)
            y = 0;

        //use 1/6 from the the sensor size for the focus rect
        int width_to_set = sizee.getWidth() / 8;
        float kProp = (float) CurUi.x / (float) (CurUi.y);
        int height_to_set = (int) (width_to_set * kProp);
        float x_scale = (float) sizee.getWidth() / (float) CurUi.y;
        float y_scale = (float) sizee.getHeight() / (float) CurUi.x;
        int x_to_set = (int) (x * x_scale) - width_to_set / 2;
        int y_to_set = (int) (y * y_scale) - height_to_set / 2;
        y_to_set = sizee.getHeight() - y_to_set - height_to_set;
        if (x_to_set < 0)
            x_to_set = 0;
        if (y_to_set < 0)
            y_to_set = 0;
        if (y_to_set + height_to_set > sizee.getHeight())
            y_to_set = sizee.getHeight() - height_to_set;
        if (x_to_set + width_to_set > sizee.getWidth())
            x_to_set = sizee.getWidth() - width_to_set;

        return new MeteringRectangle(x_to_set, y_to_set, width_to_set, height_to_set, MeteringRectangle.METERING_WEIGHT_MAX - 1);
    }

    private void triggerAutoFocus(MeteringRectangle[] rectaf, MeteringRectangle[] rectae) {
        if (CaptureController.burst) return;
        CaptureRequest.Builder builder = captureController.mPreviewRequestBuilder;
        if (builder == null) {
            Log.w(TAG, "triggerAutoFocus(): mPreviewRequestBuilder is null");
            return;
        }
        
        // Cancel any existing AF/AE triggers
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
        builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);
        captureController.rebuildPreviewBuilderOneShot();

        // Apply new regions
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, rectaf);
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, rectae);
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        
        // Force AUTO mode to ensure an active scan happens on trigger start
        // This fixes the issue where tap-to-focus is ignored in continuous modes
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AE_MODE, Math.max(PreferenceKeys.getAeMode(), 1));

        // Start triggers
        builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
        captureController.rebuildPreviewBuilderOneShot();

        // Reset triggers to IDLE for repeating requests
        builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        
        captureController.rebuildPreviewBuilder();
        isTouchFocus = true;
    }

    private void resetAutoFocus() {
        if (CaptureController.burst) return;
        CaptureRequest.Builder builder = captureController.mPreviewRequestBuilder;
        if (builder == null) {
            Log.w(TAG, "resetAutoFocus(): mPreviewRequestBuilder is null");
            return;
        }
        Log.d(TAG, "resetAutoFocus");
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
        captureController.rebuildPreviewBuilderOneShot();
        
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, captureController.mPreviewMeteringAF);
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, captureController.mPreviewMeteringAE);
        builder.set(CaptureRequest.CONTROL_AF_MODE, captureController.mPreviewAFMode);
        builder.set(CaptureRequest.CONTROL_AE_MODE, captureController.mPreviewAEMode);
        
        // Kickstart the continuous focus algorithm if we are returning to a continuous mode.
        // This ensures the camera re-evaluates the scene immediately without needing motion.
        if (captureController.mPreviewAFMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE ||
                captureController.mPreviewAFMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            captureController.rebuildPreviewBuilderOneShot();
        }

        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        captureController.rebuildPreviewBuilder();
        isTouchFocus = false;
    }


    //Thread safe
    //call when focus circle needs to be hidden immediately
    public void resetFocusCircle() {
        focusCircleView.removeCallbacks(hideFocusCircleRunnable);
        exposureCircleView.removeCallbacks(hideFocusCircleRunnable);
        focusCircleView.post(hideFocusCircleRunnable);
        exposureCircleView.post(hideFocusCircleRunnable);
        resetAutoFocus();
    }

    //Must be run on UI Thread
    private void hideFocusCircleView() {
        hideCircle(focusCircleView);
        hideCircle(exposureCircleView);
        resetAutoFocus();
    }

    private void hideCircle(View view) {
        if (view.getVisibility() == View.VISIBLE) {
            view.animate().alpha(0f).scaleY(1.8f).scaleX(1.8f).setDuration(100)
                    .withEndAction(() -> {
                        view.setVisibility(View.GONE);
                        view.setX((float) textureView.getWidth() / 2.f);
                        view.setY((float) textureView.getHeight() / 2.f);
                        view.setScaleY(1f);
                        view.setScaleX(1f);
                        view.setAlpha(1f);
                    })
                    .start();
        }
    }
}
