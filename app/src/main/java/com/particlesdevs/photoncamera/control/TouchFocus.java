package com.particlesdevs.photoncamera.control;

import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.os.SystemClock;
import android.util.Size;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.ui.camera.views.FocusCircleView;
import com.particlesdevs.photoncamera.ui.camera.views.viewfinder.GLPreview;

/** Coordinates independently movable Camera2 AF and AE metering points. */
public class TouchFocus {
    private static final int AUTO_HIDE_DELAY_MS = 5000;
    private static final int DRAG_AF_TRIGGER_INTERVAL_MS = 120;
    private enum MeteringType { AF, AE }

    private final CaptureController captureController;
    private final GLPreview textureView;
    private final FocusCircleView focusCircleView;
    private final FocusCircleView exposureCircleView;
    private final Runnable hideIndicatorsRunnable = this::hideIndicators;
    private MeteringRectangle afRegion;
    private MeteringRectangle aeRegion;
    public boolean isTouchFocus = false;

    public TouchFocus(CaptureController captureController, FocusCircleView focusCircle,
                      FocusCircleView exposureCircle, GLPreview textureView) {
        this.captureController = captureController;
        this.focusCircleView = focusCircle;
        this.exposureCircleView = exposureCircle;
        this.textureView = textureView;
        exposureCircleView.setExposureIndicator(true);
        focusCircleView.setOnTouchListener(createDragListener(MeteringType.AF));
        exposureCircleView.setOnTouchListener(createDragListener(MeteringType.AE));
        hideIndicatorsImmediately();
    }

    /** Both meters and controls start at the exact tapped point. */
    public void processTouchToFocus(float x, float y) {
        cancelAutoHide();
        float afX = clamp(x, 0, textureView.getWidth());
        showIndicator(focusCircleView, afX, y, true);
        showIndicator(exposureCircleView, afX, y, true);
        afRegion = createMeteringRectangle(afX, y);
        aeRegion = createMeteringRectangle(afX, y);
        applyMetering(true, true);
        scheduleAutoHide();
    }

    public void setState(@Nullable Integer afState) {
        if (afState != null && focusCircleView.getVisibility() == View.VISIBLE) {
            focusCircleView.setAfState(afState);
        }
    }

    private View.OnTouchListener createDragListener(MeteringType type) {
        return new View.OnTouchListener() {
            private float pointerOffsetX;
            private float pointerOffsetY;
            private long lastAfTriggerTime;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                        && type == MeteringType.AE) {
                    float dx = event.getX() - view.getWidth() / 2f;
                    float dy = event.getY() - view.getHeight() / 2f;
                    // The inner yellow sun owns AE; the outer ring falls through to AF.
                    if (Math.hypot(dx, dy) > view.getWidth() * 0.34f) return false;
                }
                float[] point = toTextureCoordinates(event);
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.getParent().requestDisallowInterceptTouchEvent(true);
                        cancelAutoHide();
                        pointerOffsetX = point[0] - indicatorCenterX(view);
                        pointerOffsetY = point[1] - indicatorCenterY(view);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        long now = SystemClock.uptimeMillis();
                        boolean triggerWhileDragging = type == MeteringType.AF
                                && now - lastAfTriggerTime >= DRAG_AF_TRIGGER_INTERVAL_MS;
                        moveMeter(type, point[0] - pointerOffsetX, point[1] - pointerOffsetY,
                                triggerWhileDragging);
                        if (triggerWhileDragging) lastAfTriggerTime = now;
                        return true;
                    case MotionEvent.ACTION_UP:
                        moveMeter(type, point[0] - pointerOffsetX, point[1] - pointerOffsetY, true);
                        view.performClick();
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                        scheduleAutoHide();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                        scheduleAutoHide();
                        return true;
                    default:
                        return false;
                }
            }
        };
    }

    private float[] toTextureCoordinates(MotionEvent event) {
        int[] location = new int[2];
        textureView.getLocationOnScreen(location);
        return new float[]{event.getRawX() - location[0], event.getRawY() - location[1]};
    }

    private void moveMeter(MeteringType type, float x, float y, boolean trigger) {
        x = clamp(x, 0, textureView.getWidth());
        y = clamp(y, 0, textureView.getHeight());
        if (type == MeteringType.AF) {
            showIndicator(focusCircleView, x, y, false);
            afRegion = createMeteringRectangle(x, y);
            applyMetering(trigger, false);
        } else {
            showIndicator(exposureCircleView, x, y, false);
            aeRegion = createMeteringRectangle(x, y);
            applyMetering(false, trigger);
        }
    }

    private void showIndicator(View view, float x, float y, boolean animate) {
        view.animate().cancel();
        // Indicators stay INVISIBLE (not GONE), so ConstraintLayout measures both during
        // initial layout. Their identical measured centers make the very first AF/AE tap
        // use the same coordinate without waiting for a second layout pass.
        positionIndicator(view, x, y);
        view.setAlpha(1f);
        view.setVisibility(View.VISIBLE);
        if (animate) {
            view.setScaleX(1.2f);
            view.setScaleY(1.2f);
            view.animate().scaleX(1f).scaleY(1f).setDuration(250).start();
        }
    }

    private void positionIndicator(View view, float x, float y) {
        float targetCenterX = textureView.getX() + x;
        float targetCenterY = textureView.getY() + y;
        float layoutCenterX = view.getLeft() + view.getWidth() / 2f;
        float layoutCenterY = view.getTop() + view.getHeight() / 2f;
        view.setTranslationX(targetCenterX - layoutCenterX);
        view.setTranslationY(targetCenterY - layoutCenterY);
    }

    private float indicatorCenterX(View view) {
        return view.getX() - textureView.getX() + view.getWidth() / 2f;
    }

    private float indicatorCenterY(View view) {
        return view.getY() - textureView.getY() + view.getHeight() / 2f;
    }

    /** Maps portrait viewfinder coordinates to the app's rotated sensor preview. */
    private MeteringRectangle createMeteringRectangle(float viewX, float viewY) {
        if (captureController.mImageReaderPreview == null || CaptureController.mCameraCharacteristics == null
                || textureView.getWidth() <= 0 || textureView.getHeight() <= 0) return null;

        Point previewSize = new Point(captureController.mImageReaderPreview.getWidth(),
                captureController.mImageReaderPreview.getHeight());
        Point uiSize = new Point(textureView.getWidth(), textureView.getHeight());
        Rect activeArray = CaptureController.mCameraCharacteristics.get(
                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Size sensorSize = activeArray != null
                ? new Size(activeArray.width(), activeArray.height())
                : CaptureController.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        if (sensorSize == null) sensorSize = new Size(previewSize.x, previewSize.y);

        // PR #176 mapping: the rotated portrait preview passes (screenY, screenX).
        int x = Math.max(0, Math.round(viewY));
        int y = Math.max(0, Math.round(viewX));
        int regionWidth = sensorSize.getWidth() / 8;
        float uiAspect = (float) uiSize.x / uiSize.y;
        int regionHeight = Math.max(1, (int) (regionWidth * uiAspect));
        float xScale = (float) sensorSize.getWidth() / uiSize.y;
        float yScale = (float) sensorSize.getHeight() / uiSize.x;
        int left = (int) (x * xScale) - regionWidth / 2;
        int top = (int) (y * yScale) - regionHeight / 2;
        top = sensorSize.getHeight() - top - regionHeight;
        left = Math.max(0, Math.min(left, sensorSize.getWidth() - regionWidth));
        top = Math.max(0, Math.min(top, sensorSize.getHeight() - regionHeight));
        return new MeteringRectangle(left, top, regionWidth, regionHeight,
                MeteringRectangle.METERING_WEIGHT_MAX - 1);
    }

    private void applyMetering(boolean triggerAf, boolean triggerAe) {
        if (CaptureController.burst) return;
        CaptureRequest.Builder builder = captureController.mPreviewRequestBuilder;
        if (builder == null || CaptureController.mCameraCharacteristics == null) return;

        Integer maxAf = CaptureController.mCameraCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        Integer maxAe = CaptureController.mCameraCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        boolean supportsAfRegion = maxAf != null && maxAf > 0 && afRegion != null;
        boolean supportsAeRegion = maxAe != null && maxAe > 0 && aeRegion != null;

        if (triggerAf && supportsAfRegion) {
            // Match PR #176's AF state transition exactly:
            // CAF/passive -> CANCEL -> AUTO + START -> ACTIVE_SCAN -> *_LOCKED.
            // In particular, do not submit an AUTO + IDLE request between CANCEL and START;
            // some HALs consume that request by returning to INACTIVE without scanning.
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);
            captureController.rebuildPreviewBuilderOneShot();

            builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{afRegion});
            if (supportsAeRegion) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{aeRegion});
            }
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AE_MODE, Math.max(PreferenceKeys.getAeMode(), 1));
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            if (triggerAe && supportsAeRegion) {
                builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            } else {
                builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
            }
            isTouchFocus = true;
            captureController.rebuildPreviewBuilderOneShot();

            // START is edge-triggered. Keep AUTO and the tap regions in the repeating
            // request, but return both trigger fields to IDLE exactly as PR #176 does.
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
        } else if (triggerAe && supportsAeRegion) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{aeRegion});
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            isTouchFocus = true;
            captureController.rebuildPreviewBuilderOneShot();
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
        } else {
            // Region-only updates are used between throttled drag triggers.
            if (supportsAfRegion) {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{afRegion});
            }
            if (supportsAeRegion) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{aeRegion});
            }
        }
        captureController.rebuildPreviewBuilder();
        isTouchFocus = isTouchFocus || supportsAfRegion || supportsAeRegion;
    }

    private void resetAutoFocus() {
        if (CaptureController.burst) return;
        CaptureRequest.Builder builder = captureController.mPreviewRequestBuilder;
        if (builder == null) return;
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
        captureController.rebuildPreviewBuilderOneShot();
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
        Integer maxAf = CaptureController.mCameraCharacteristics == null ? null
                : CaptureController.mCameraCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        Integer maxAe = CaptureController.mCameraCharacteristics == null ? null
                : CaptureController.mCameraCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        if (maxAf != null && maxAf > 0) {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, captureController.mPreviewMeteringAF);
        }
        if (maxAe != null && maxAe > 0) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, captureController.mPreviewMeteringAE);
        }
        builder.set(CaptureRequest.CONTROL_AF_MODE, captureController.mPreviewAFMode);
        builder.set(CaptureRequest.CONTROL_AE_MODE, captureController.mPreviewAEMode);
        captureController.rebuildPreviewBuilder();
        afRegion = null;
        aeRegion = null;
        isTouchFocus = false;
    }

    public void resetFocusCircle() {
        cancelAutoHide();
        focusCircleView.post(this::hideIndicatorsImmediately);
        resetAutoFocus();
    }

    private void scheduleAutoHide() { focusCircleView.postDelayed(hideIndicatorsRunnable, AUTO_HIDE_DELAY_MS); }
    private void cancelAutoHide() { focusCircleView.removeCallbacks(hideIndicatorsRunnable); }

    private void hideIndicators() {
        hideIndicator(focusCircleView);
        hideIndicator(exposureCircleView);
        if (isTouchFocus) resetAutoFocus();
    }

    private void hideIndicator(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        view.animate().alpha(0f).scaleX(1.4f).scaleY(1.4f).setDuration(120)
                .withEndAction(() -> {
                    // INVISIBLE preserves the measured size/position for the first tap after
                    // launch, camera resume, or an auto-hide.
                    view.setVisibility(View.INVISIBLE);
                    view.setAlpha(1f);
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                }).start();
    }

    private void hideIndicatorsImmediately() {
        focusCircleView.animate().cancel();
        exposureCircleView.animate().cancel();
        focusCircleView.setVisibility(View.INVISIBLE);
        exposureCircleView.setVisibility(View.INVISIBLE);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }
}
