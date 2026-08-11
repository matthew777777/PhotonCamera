package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import android.content.Context;
import android.graphics.*;
import android.text.StaticLayout;
import android.text.Layout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import com.particlesdevs.photoncamera.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.particlesdevs.photoncamera.processing.ProcessingLog;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

public class SurfaceViewOverViewfinder extends SurfaceView {

    private static final String TAG = "SurfaceViewOverViewfinder";
    private final SurfaceHolder mHolder;
    private final float screenRatio;
    private final Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    private final Paint rectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint histogramPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PorterDuffXfermode porterDuffXfermode = new PorterDuffXfermode(PorterDuff.Mode.ADD);
    private final Paint histogramBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    public boolean isCanvasDrawn = false;
    private RectF afRectToDraw = new RectF();
    private RectF aeRectToDraw = new RectF();
    private String debugText = null;
    private int[][] histogramData;
    private int rotationDegrees;

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

        histogramPaint.setColor(Color.WHITE);
        histogramPaint.setStyle(Paint.Style.FILL);

        histogramBackgroundPaint.setARGB(100, 0, 0, 0);
        histogramBackgroundPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // We use drawOnCanvas via refresh() instead of onDraw
        // to avoid double rendering on the surface.
    }

    private void drawHistogram(Canvas canvas) {
        if (!PreferenceKeys.isShowHistogramOn() || histogramData == null) return;

        final int histWidth = 256;
        final int histHeight = 100;
        final int margin = (Math.abs(rotationDegrees) == 90) ? 80 : 20;

        // Fixed physical position: top-left of the screen
        float left = margin;
        float top = margin;

        canvas.save();
        // Rotate in place around the histogram's center
        canvas.rotate(rotationDegrees, left + histWidth / 2f, top + histHeight / 2f);

        canvas.drawRect(left, top, left + histWidth, top + histHeight, histogramBackgroundPaint);

        float maxVal = 0;
        // Ignore the very first and last bins as they often have spikes (pure black/white)
        for (int i = 0; i < 3; i++) {
            for (int j = 1; j < histogramData[i].length - 1; j++) {
                if (histogramData[i][j] > maxVal) maxVal = histogramData[i][j];
            }
        }

        if (maxVal > 0) {
            histogramPaint.setXfermode(porterDuffXfermode);
            for (int i = 0; i < 3; i++) {
                if (i == 0) {
                    histogramPaint.setARGB(0xFF, 0xFF, 0x07, 0x00);
                } else if (i == 1) {
                    histogramPaint.setARGB(0xFF, 0x00, 0xC9, 0x0D);
                } else {
                    histogramPaint.setARGB(0xFF, 0x19, 0x24, 0xB1);
                }

                Path histPath = new Path();
                histPath.moveTo(left, top + histHeight);
                for (int j = 0; j < histogramData[i].length; j++) {
                    float barHeight = Math.min(histHeight, (histogramData[i][j] / maxVal) * histHeight);
                    histPath.lineTo(left + j, top + histHeight - barHeight);
                }
                histPath.lineTo(left + histWidth, top + histHeight);
                histPath.close();
                canvas.drawPath(histPath, histogramPaint);
            }
            histogramPaint.setXfermode(null);
        }
        canvas.restore();
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
            canvas.save();
            path.reset();
            path.addRoundRect(new RectF(canvas.getClipBounds()), 40, 40, Path.Direction.CW);
            path.setFillType(Path.FillType.INVERSE_EVEN_ODD);
            canvas.clipPath(path);
            canvas.drawColor(Color.BLACK);
            canvas.restore();
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

    public void setHistogramData(int[][] data) {
        this.histogramData = data;
    }

    public void setRotation(int degrees) {
        this.rotationDegrees = degrees;
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
                drawGrid(canvas);
                drawRoundEdges(canvas);
                drawHistogram(canvas);
                drawAFRect(canvas);
                drawAERect(canvas);
                drawAFDebugText(canvas);
                surfaceHolder.unlockCanvasAndPost(canvas);
                isCanvasDrawn = true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void clear() {
        afRectToDraw = null;
        aeRectToDraw = null;
        debugText = null;
        // Don't nullify histogramData here to prevent flickering
        try {
            Canvas canvas = mHolder.lockHardwareCanvas();
            if (canvas != null) {
                canvas.drawColor(0, PorterDuff.Mode.CLEAR);
                mHolder.unlockCanvasAndPost(canvas);
            }
            // Clear twice to handle double buffering
            canvas = mHolder.lockHardwareCanvas();
            if (canvas != null) {
                canvas.drawColor(0, PorterDuff.Mode.CLEAR);
                mHolder.unlockCanvasAndPost(canvas);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        isCanvasDrawn = false;
    }

    public void forceClear() {
        histogramData = null;
        clear();
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

