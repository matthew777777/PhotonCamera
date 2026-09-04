package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.*;
import android.text.TextPaint;
import android.view.animation.DecelerateInterpolator;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import com.particlesdevs.photoncamera.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;

public class SurfaceViewOverViewfinder extends SurfaceView {

    private static final String TAG = "SurfaceViewOverViewfinder";
    private final SurfaceHolder mHolder;
    private final float screenRatio;
    private final Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    private final Paint rectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    public boolean isCanvasDrawn = false;
    private RectF afRectToDraw = new RectF();
    private RectF aeRectToDraw = new RectF();
    private String debugText = null;
    private final TextPaint hudPaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    private final Paint strikePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float oisTextWidth = 0f;
    private float mDensity = 1.0f;

    private String mExpoText = null;
    private String mIsoText = null;
    private String mFocalText = null;
    private String mFocusText = null;
    private String mWbText = null;
    private boolean mIsTripod = false;
    private boolean mIsOisSupported = false;
    private boolean mIsOisActive = false;
    private float mAnimatedOrientation = 0f;
    private int mTargetOrientation = 0;
    private ValueAnimator mRotationAnimator = null;

    private final Paint histBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint histBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint histChannelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path histPath = new Path();
    private final PorterDuffXfermode histXfermode = new PorterDuffXfermode(PorterDuff.Mode.ADD);
    private int[][] mHistColorsMap = null;
    private int mHistMaxY = 1;
    private int mHistSize = 64;

    public SurfaceViewOverViewfinder(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.setZOrderOnTop(true);
        mHolder = this.getHolder();
        mHolder.setFormat(PixelFormat.TRANSPARENT);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenRatio = (float) Math.max(dm.heightPixels, dm.widthPixels) / Math.min(dm.heightPixels, dm.widthPixels);
        initPaints();
    }

    private void initPaints() {
        whitePaint.setColor(Color.WHITE);
        whitePaint.setStrokeWidth(1.5f);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(25);
        textPaint.setTextAlign(Paint.Align.LEFT);

        rectPaint.setStyle(Paint.Style.STROKE);
        rectPaint.setStrokeWidth(3);

        mDensity = getResources().getDisplayMetrics().density;
        float fontSize = 13f * mDensity;

        hudPaint.setColor(Color.WHITE);
        hudPaint.setTextSize(fontSize);
        hudPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hudPaint.setTextAlign(Paint.Align.LEFT);
        hudPaint.setShadowLayer(4f, 1f, 1f, Color.BLACK);

        strikePaint.setColor(Color.WHITE);
        strikePaint.setStrokeWidth(1.5f * mDensity);
        strikePaint.setStyle(Paint.Style.STROKE);
        strikePaint.setShadowLayer(4f, 1f, 1f, Color.BLACK);

        oisTextWidth = hudPaint.measureText("OIS");

        histBgPaint.setColor(Color.argb(100, 0, 0, 0));
        histBgPaint.setStyle(Paint.Style.FILL);

        histBorderPaint.setColor(Color.argb(90, 255, 255, 255));
        histBorderPaint.setStyle(Paint.Style.STROKE);
        histBorderPaint.setStrokeWidth(1.0f * mDensity);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // PreferenceKeys is uninitialized in the layout editor, skip overlay drawing there.
        if (isInEditMode()) return;
        drawGrid(canvas);
        drawRoundEdges(canvas);
    }

    private void drawGrid(Canvas canvas) {
        switch (PreferenceKeys.getGridValue()) {
            case 1:
                draw3x3(canvas);
                break;
            case 2:
                draw4x4(canvas);
                break;
            case 3:
                drawGoldenRatio(canvas);
                break;
            case 4:
                drawSuperDiag(canvas);
                break;
            default:
                break;
        }
    }

    private void draw3x3(Canvas canvas) {
        int w = canvas.getWidth();
        int h = canvas.getHeight();
        canvas.drawLine(w / 3f, 0, w / 3f, h, whitePaint);
        canvas.drawLine(2.f * w / 3f, 0, 2f * w / 3f, h, whitePaint);
        canvas.drawLine(0, h / 3f, w, h / 3f, whitePaint);
        canvas.drawLine(0, 2f * h / 3f, w, 2f * h / 3f, whitePaint);
    }

    private void draw4x4(Canvas canvas) {
        int w = canvas.getWidth();
        int h = canvas.getHeight();
        canvas.drawLine(w / 4f, 0, w / 4f, h, whitePaint);
        canvas.drawLine(w / 2f, 0, w / 2f, h, whitePaint);
        canvas.drawLine(3 * w / 4f, 0, 3 * w / 4f, h, whitePaint);
        canvas.drawLine(0, h / 4f, w, h / 4f, whitePaint);
        canvas.drawLine(0, h / 2f, w, h / 2f, whitePaint);
        canvas.drawLine(0, 3 * h / 4f, w, 3 * h / 4f, whitePaint);
    }

    private void drawGoldenRatio(Canvas canvas) {
        int w = canvas.getWidth();
        int h = canvas.getHeight();
        float gr = (float) goldenRatio(1, 1);
        canvas.drawLine(w / (1 + gr), 0, w / (1 + gr), h, whitePaint);
        canvas.drawLine(gr * w / (1 + gr), 0, gr * w / (1 + gr), h, whitePaint);
        canvas.drawLine(0, h / (1 + gr), w, h / (1 + gr), whitePaint);
        canvas.drawLine(0, gr * h / (1 + gr), w, gr * h / (1 + gr), whitePaint);
    }

    private void drawSuperDiag(Canvas canvas) {
        int w = canvas.getWidth();
        int h = canvas.getHeight();
        //float gr = (float) goldenRatio(1, 1);
        canvas.drawLine(0, 0, w, h, whitePaint);
        canvas.drawLine(w/3.f, h/3.f, w, 0, whitePaint);
        canvas.drawLine(2.f*w/3.f, 2.f*h/3.f, 0, h, whitePaint);
    }

    private double goldenRatio(double a, double b) {
        double e = 0.00001;
        if (Math.abs((b / a) - ((a + b) / b)) < e) {
            return ((a + b) / b);
        } else {
            return goldenRatio(b, a + b);
        }
    }

    private void drawRoundEdges(Canvas canvas) {
        if (PreferenceKeys.isRoundEdgeOn()) {
            path.reset();
            path.addRoundRect(new RectF(canvas.getClipBounds()), 40, 40, Path.Direction.CW);
            path.setFillType(Path.FillType.INVERSE_EVEN_ODD);
            canvas.clipPath(path);
            canvas.drawColor(Color.BLACK);
        }
    }

    public void setAFRect(RectF rect) {
        this.afRectToDraw = rect;
    }

    public void setAERect(RectF rect) {
        this.aeRectToDraw = rect;
    }

    public void setDebugText(String debugText) {
        this.debugText = debugText;
    }

    public void setHudData(String expo, String iso, String focal, String focus, String wb,
                           boolean isTripod, boolean isOisSupported, boolean isOisActive) {
        this.mExpoText = expo;
        this.mIsoText = iso;
        this.mFocalText = focal;
        this.mFocusText = focus;
        this.mWbText = wb;
        this.mIsTripod = isTripod;
        this.mIsOisSupported = isOisSupported;
        this.mIsOisActive = isOisActive;
    }

    /**
     * Smoothly animates HUD and Histogram rotation to the target device orientation (250ms).
     * Calculates the shortest angular path to prevent unwanted 360-degree spins.
     */
    public void setOrientation(int orientation) {
        if (this.mTargetOrientation == orientation) return;
        this.mTargetOrientation = orientation;

        float startAngle = mAnimatedOrientation;
        float diff = (orientation - startAngle) % 360f;
        if (diff > 180f) diff -= 360f;
        if (diff < -180f) diff += 360f;
        float endAngle = startAngle + diff;

        if (mRotationAnimator != null && mRotationAnimator.isRunning()) {
            mRotationAnimator.cancel();
        }

        mRotationAnimator = ValueAnimator.ofFloat(startAngle, endAngle);
        mRotationAnimator.setDuration(250);
        mRotationAnimator.setInterpolator(new DecelerateInterpolator());
        mRotationAnimator.addUpdateListener(animation -> {
            mAnimatedOrientation = (float) animation.getAnimatedValue();
            refresh();
        });
        mRotationAnimator.start();
    }

    public void refresh() {
        drawOnCanvas(mHolder);
    }

    private void drawOnCanvas(SurfaceHolder surfaceHolder) {
        try {
            Canvas canvas = surfaceHolder.lockHardwareCanvas();
            if (canvas == null) {
                Log.e(TAG, "Canvas is null");
            } else {
                canvas.drawColor(0, PorterDuff.Mode.CLEAR);//Clears the canvas
                int mode = PreferenceKeys.getAfDataValue();
                if (mode == 1) {
                    drawHUD(canvas);
                } else if (mode == 2) {
                    drawHUD(canvas);
                    drawHistogram(canvas);
                } else if (mode == 3) {
                    drawAFRect(canvas);
                    drawAERect(canvas);
                    drawAFDebugText(canvas);
                }
                surfaceHolder.unlockCanvasAndPost(canvas);
                isCanvasDrawn = true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void drawHUD(Canvas canvas) {
        if (mExpoText == null) return;

        float marginSide = 14f * mDensity;
        float marginTop = 12f * mDensity;
        float lineSpacing = 16f * mDensity;

        int rowCount = 5 + (mIsTripod ? 1 : 0) + (mIsOisSupported ? 1 : 0);
        float hudHeight = rowCount * lineSpacing;
        float hudWidth = 64f * mDensity;

        boolean isLandscape = (mTargetOrientation == 90 || mTargetOrientation == 270);

        // Dynamic pivot calculation keeping exact 14dp left and 12dp top margins in all rotations
        float pivotX = marginSide + (isLandscape ? (hudHeight / 2f) : (hudWidth / 2f));
        float pivotY = marginTop + (isLandscape ? (hudWidth / 2f) : (hudHeight / 2f));

        float x = pivotX - (hudWidth / 2f);
        float y = pivotY - (hudHeight / 2f) + (10f * mDensity); // 10dp text baseline

        canvas.save();
        if (mAnimatedOrientation != 0f) {
            canvas.rotate(mAnimatedOrientation, pivotX, pivotY);
        }

        // Row 1: Shutter Speed
        canvas.drawText(mExpoText, x, y, hudPaint);
        y += lineSpacing;

        // Row 2: ISO
        canvas.drawText(mIsoText, x, y, hudPaint);
        y += lineSpacing;

        // Row 3: Lens Specs (Focal Length & Aperture)
        canvas.drawText(mFocalText, x, y, hudPaint);
        y += lineSpacing;

        // Row 4: Focus Mode & Distance
        canvas.drawText(mFocusText, x, y, hudPaint);
        y += lineSpacing;

        // Row 5: White Balance / CCT in Kelvin
        if (mWbText != null) {
            canvas.drawText(mWbText, x, y, hudPaint);
            y += lineSpacing;
        }

        // Row 6: OIS Status (only if active lens has hardware OIS)
        if (mIsOisSupported) {
            canvas.drawText("OIS", x, y, hudPaint);

            // Draw clean strike-through line if OIS is currently inactive
            if (!mIsOisActive) {
                float strikeY = y - (hudPaint.getTextSize() * 0.32f);
                canvas.drawLine(x - 2f * mDensity, strikeY, x + oisTextWidth + 2f * mDensity, strikeY, strikePaint);
            }
            y += lineSpacing;
        }

        // Row 7: Tripod Status (only when mounted on a tripod)
        if (mIsTripod) {
            canvas.drawText("[TRIPOD]", x, y, hudPaint);
            y += lineSpacing;
        }

        canvas.restore();
    }

    public void setHistogramData(int[][] colorsMap, int maxY, int size) {
        this.mHistColorsMap = colorsMap;
        this.mHistMaxY = Math.max(1, maxY);
        this.mHistSize = size;
    }

    private void drawHistogram(Canvas canvas) {
        if (mHistColorsMap == null || mHistColorsMap.length < 3) return;

        float marginSide = 14f * mDensity;
        float marginTop = 12f * mDensity;
        float w = 72f * mDensity;
        float h = 36f * mDensity;

        boolean isLandscape = (mTargetOrientation == 90 || mTargetOrientation == 270);

        // Dynamic pivot calculation keeping exact 14dp right and 12dp top margins in all rotations
        float pivotX = canvas.getWidth() - marginSide - (isLandscape ? (h / 2f) : (w / 2f));
        float pivotY = marginTop + (isLandscape ? (w / 2f) : (h / 2f));

        float left = pivotX - (w / 2f);
        float top = pivotY - (h / 2f);
        float right = left + w;
        float bottom = top + h;

        canvas.save();
        if (mAnimatedOrientation != 0f) {
            canvas.rotate(mAnimatedOrientation, pivotX, pivotY);
        }

        // 1. Draw semi-transparent dark background and 1/3 guidelines
        canvas.drawRect(left, top, right, bottom, histBgPaint);
        canvas.drawRect(left, top, right, bottom, histBorderPaint);
        canvas.drawLine(left + (w / 3f), top, left + (w / 3f), bottom, histBorderPaint);
        canvas.drawLine(left + (2f * w / 3f), top, left + (2f * w / 3f), bottom, histBorderPaint);

        // 2. Draw additive RGB channels (Red, Green, Blue)
        float xInterval = w / (float) (mHistSize + 1);
        histChannelPaint.setAntiAlias(true);
        histChannelPaint.setStyle(Paint.Style.FILL);
        histChannelPaint.setXfermode(histXfermode);

        for (int i = 0; i < 3; i++) {
            if (i == 0) {
                histChannelPaint.setARGB(0xDD, 0xFF, 0x1A, 0x1A); // Red channel
            } else if (i == 1) {
                histChannelPaint.setARGB(0xDD, 0x1A, 0xD4, 0x2A); // Green channel
            } else {
                histChannelPaint.setARGB(0xDD, 0x2A, 0x55, 0xFF); // Blue channel
            }

            histPath.reset();
            histPath.moveTo(left, bottom);
            for (int j = 0; j < mHistSize; j++) {
                float val = ((float) mHistColorsMap[i][j] * (h / (float) mHistMaxY));
                float px = left + (j * xInterval);
                float py = bottom - Math.min(val, h);
                histPath.lineTo(px, py);
            }
            histPath.lineTo(left + (mHistSize * xInterval), bottom);
            canvas.drawPath(histPath, histChannelPaint);
        }

        canvas.restore();
    }

    public void clear() {
        if (mRotationAnimator != null) {
            mRotationAnimator.cancel();
        }
        try {
            Canvas canvas = mHolder.lockHardwareCanvas();
            if (canvas == null) {
                Log.e(TAG, "Canvas is null");
            } else {
                canvas.drawColor(0, PorterDuff.Mode.CLEAR);//Clears the canvas
                mHolder.unlockCanvasAndPost(canvas);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        afRectToDraw = null;
        aeRectToDraw = null;
        debugText = null;
        mExpoText = null;
        mIsoText = null;
        mFocalText = null;
        mFocusText = null;
        mWbText = null;
        mHistColorsMap = null;
        isCanvasDrawn = false;
    }

    private void drawAFDebugText(Canvas canvas) {
        if (PreferenceKeys.isAfDataOn()) {
            if (debugText != null) {
                int y = 180;
                if (screenRatio > 16 / 9f) y = 50;
                for (String line : debugText.split("\n")) {
                    if (line.contains("AF_RECT")) {
                        textPaint.setColor(Color.GREEN);
                    } else if (line.contains("AE_RECT")) {
                        textPaint.setColor(Color.YELLOW);
                    } else {
                        textPaint.setColor(Color.WHITE);
                    }
                    canvas.drawText(line, 50, y, textPaint);
                    y += textPaint.descent() - textPaint.ascent();
                }
            }
        }
    }

    private void drawAFRect(Canvas canvas) {
        if (PreferenceKeys.isAfDataOn()) {
            if (afRectToDraw != null && !afRectToDraw.isEmpty()) {
                rectPaint.setColor(Color.GREEN);
                canvas.drawRect(afRectToDraw, rectPaint);
            }
        }
    }

    private void drawAERect(Canvas canvas) {
        if (PreferenceKeys.isAfDataOn()) {
            if (aeRectToDraw != null && !aeRectToDraw.isEmpty()) {
                rectPaint.setColor(Color.YELLOW);
                canvas.drawRect(aeRectToDraw, rectPaint);
            }
        }
    }
}

