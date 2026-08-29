package com.particlesdevs.photoncamera.ui.camera.views;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.hardware.camera2.CaptureResult;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.R;

import java.util.function.Function;

/**
 * Created by Vibhor on 11/01/2021
 */
public class FocusCircleView extends View {
    private static final int[] STATE_FOCUSED_LOCKED = {R.attr.focused_locked};
    private static final int[] STATE_UNFOCUSED_LOCKED = {R.attr.unfocused_locked};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ColorStateList colorStateList;
    private boolean focused_locked;
    private boolean unfocused_locked;
    private boolean exposureIndicator;

    public FocusCircleView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.getTheme().obtainStyledAttributes(
                attrs,
                R.styleable.FocusCircleView,
                0, 0
        );
        colorStateList = a.getColorStateList(R.styleable.FocusCircleView_android_color);
        float thickness = a.getDimension(R.styleable.FocusCircleView_android_thickness, 2.5f);
        if (colorStateList == null)
            colorStateList = ColorStateList.valueOf(Color.WHITE);
        a.recycle();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(thickness);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        paint.setColor(getPaintColor());
        if (!exposureIndicator) {
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, getWidth() / 2.5f, paint);
        } else {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float inner = getWidth() / 7f;
            float rayStart = getWidth() / 4.5f;
            float rayEnd = getWidth() / 3.2f;
            canvas.drawCircle(cx, cy, inner, paint);
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4d;
                canvas.drawLine(cx + (float) Math.cos(angle) * rayStart,
                        cy + (float) Math.sin(angle) * rayStart,
                        cx + (float) Math.cos(angle) * rayEnd,
                        cy + (float) Math.sin(angle) * rayEnd, paint);
            }
        }
    }

    private int getPaintColor() {
        Function<int[], Integer> color = mode -> colorStateList.getColorForState(mode, colorStateList.getDefaultColor());
        if (focused_locked)
            return color.apply(STATE_FOCUSED_LOCKED);
        if (unfocused_locked)
            return color.apply(STATE_UNFOCUSED_LOCKED);
        return colorStateList.getDefaultColor();
    }

    public void setAfState(int afState) {
        focused_locked = false;
        unfocused_locked = false;
        switch (afState) {
            case CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED:
                focused_locked = true;
                break;
            case CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED:
                unfocused_locked = true;
                break;
            default:
                break;
        }
        invalidate();
    }

    public void setExposureIndicator(boolean exposureIndicator) {
        this.exposureIndicator = exposureIndicator;
        invalidate();
    }
}
