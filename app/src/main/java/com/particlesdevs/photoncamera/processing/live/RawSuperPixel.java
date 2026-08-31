package com.particlesdevs.photoncamera.processing.live;

import android.hardware.camera2.params.BlackLevelPattern;
import android.media.Image;
import android.util.Rational;

import com.particlesdevs.photoncamera.ui.camera.views.viewfinder.RawPreviewFrame;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.render.ColorCorrectionTransform;
import com.particlesdevs.photoncamera.processing.render.Parameters;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * JNI RAW16 to RGBA8 path for the RAW viewfinder. The native side samples the
 * direct camera buffer in place (honouring row/pixel strides) and writes
 * linear (black-level-subtracted, white-level-normalized) RGBA into the
 * pre-allocated direct output buffer below, neutral-anchored by scaling each
 * channel by min(whitePoint)/whitePoint so shadows keep their uint8 range.
 * White-balance gains, the white point ratios and gamma encoding are applied
 * later by StreamedPostPipeline.
 */
public final class RawSuperPixel {
    public static final int OUTPUT_WIDTH = 320;
    public static final int OUTPUT_HEIGHT = 240;
    private static final int BUFFER_COUNT = 3;
    private static final ByteBuffer[] OUTPUTS = new ByteBuffer[BUFFER_COUNT];
    private static final ByteBuffer[] TONE_CURVES = new ByteBuffer[BUFFER_COUNT];
    private static final boolean[] IN_USE = new boolean[BUFFER_COUNT];
    private static final Runnable[] RELEASERS = new Runnable[BUFFER_COUNT];
    private static final float[] IDENTITY_GAIN_MAP = {1.f, 1.f, 1.f, 1.f};
    private static final ThreadLocal<CpuGainCache> CPU_GAIN_CACHE =
            ThreadLocal.withInitial(CpuGainCache::new);

    private static final class CpuGainCache {
        float[] map;
        int mapWidth;
        int mapHeight;
        int frameWidth;
        int frameHeight;
        float cropScaleX;
        float cropScaleY;
    }

    static {
        System.loadLibrary("rawSuperPixel");
        for (int i = 0; i < BUFFER_COUNT; i++) {
            final int slot = i;
            OUTPUTS[i] = ByteBuffer.allocateDirect(OUTPUT_WIDTH * OUTPUT_HEIGHT * 4)
                    .order(ByteOrder.nativeOrder());
            // exposure, black percentile, median, white percentile
            TONE_CURVES[i] = ByteBuffer.allocateDirect(4 * Float.BYTES)
                    .order(ByteOrder.nativeOrder());
            RELEASERS[i] = () -> release(slot);
        }
    }

    private RawSuperPixel() {}

    public static RawPreviewFrame process(Image image, int cfa, BlackLevelPattern blackPattern,
                                          int whiteLevel, Rational[] neutralColorPoint,
                                          com.particlesdevs.photoncamera.processing.render.Parameters parameters) {
        int slot = acquire();
        if (slot < 0) return null;
        ByteBuffer output = OUTPUTS[slot];
        ByteBuffer toneCurve = TONE_CURVES[slot];
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer raw = plane.getBuffer().duplicate().order(ByteOrder.nativeOrder());
        int sourceWidth = image.getWidth();
        int sourceHeight = image.getHeight();
        int cropWidth = Math.min(sourceWidth, sourceHeight * 4 / 3) & ~1;
        int cropHeight = Math.min(sourceHeight, sourceWidth * 3 / 4) & ~1;
        int cropLeft = ((sourceWidth - cropWidth) / 2) & ~1;
        int cropTop = ((sourceHeight - cropHeight) / 2) & ~1;

        float[] black = new float[4];
        if (blackPattern != null) {
            for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++)
                black[y * 2 + x] = blackPattern.getOffsetForIndex(x, y);
        }
        float gainR = neutralGain(neutralColorPoint, 0);
        float gainG = neutralGain(neutralColorPoint, 1);
        float gainB = neutralGain(neutralColorPoint, 2);
        float gainScale = 1.0f / Math.max(gainG, 1.0e-6f);
        gainR *= gainScale;
        gainG *= gainScale;
        gainB *= gainScale;

        float[] whitePoint = parameters != null && parameters.whitePoint != null
                && parameters.whitePoint.length == 3
                ? parameters.whitePoint : new float[]{1.f, 1.f, 1.f};
        try {
            toneCurve.position(0);
            process(raw, output, toneCurve, plane.getRowStride(), plane.getPixelStride(),
                    cropLeft, cropTop, cropWidth, cropHeight, cfa, black, whiteLevel,
                    whitePoint[0], whitePoint[1], whitePoint[2],
                    gainR, gainG, gainB,
                    (float) PhotonCamera.getSettings().exposureCompensation,
                    (float) PhotonCamera.getSettings().compressor);
            toneCurve.position(0);
            return new RawPreviewFrame(OUTPUT_WIDTH, OUTPUT_HEIGHT, output,
                    image.getTimestamp(), RELEASERS[slot], new float[]{gainR, gainG, gainB},
                    toneCurve, parameters);
        } catch (RuntimeException error) {
            release(slot);
            throw error;
        }
    }

    private static synchronized int acquire() {
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (!IN_USE[i]) {
                IN_USE[i] = true;
                return i;
            }
        }
        return -1;
    }

    private static synchronized void release(int slot) {
        IN_USE[slot] = false;
    }

    private static native void process(ByteBuffer raw, ByteBuffer out, ByteBuffer toneCurve,
                                       int rowStride, int pixelStride,
                                       int cropLeft, int cropTop, int cropWidth, int cropHeight,
                                       int cfa, float[] black, int whiteLevel,
                                       float whiteR, float whiteG, float whiteB,
                                       float gainR, float gainG, float gainB,
                                       float exposureCompensation, float compressor);

    /** Native CPU equivalent of the streamed preview's color and tone nodes. */
    public static void postProcessCpu(ByteBuffer pixels, int width, int height,
                                      float[] fallbackGains, ByteBuffer toneCurve,
                                      Parameters parameters) {
        float[] sensor = identityMatrix();
        float[] output = identityMatrix();
        float[] neutral = fallbackGains != null && fallbackGains.length >= 3
                ? fallbackGains : new float[]{1.f, 1.f, 1.f};
        float[] whiteScale = {1.f, 1.f, 1.f};
        float[] gainMap = IDENTITY_GAIN_MAP;
        int gainWidth = 1;
        int gainHeight = 1;
        float cropScaleX = 1.f;
        float cropScaleY = 1.f;
        if (parameters != null) {
            if (parameters.whitePoint != null && parameters.whitePoint.length == 3) {
                float minimum = Float.MAX_VALUE;
                for (float value : parameters.whitePoint)
                    if (value > 0.f && value < minimum) minimum = value;
                if (minimum != Float.MAX_VALUE) for (int i = 0; i < 3; i++)
                    whiteScale[i] = parameters.whitePoint[i] > 0.f
                            ? parameters.whitePoint[i] / minimum : 1.f;
            }
            if (parameters.CCT != null && parameters.sensorToProPhoto != null) {
                sensor = parameters.sensorToProPhoto;
                output = parameters.CCT.correctionMode
                        == ColorCorrectionTransform.CorrectionMode.MATRIXES
                        ? parameters.CCT.combineMatrix(parameters.whitePoint)
                        : parameters.CCT.matrix;
                neutral = new float[]{1.f, 1.f, 1.f};
            }
            if (parameters.hasGainMap && parameters.gainMap != null
                    && parameters.mapSize != null && parameters.gainMap.length >= 4) {
                gainMap = parameters.gainMap;
                gainWidth = parameters.mapSize.x;
                gainHeight = parameters.mapSize.y;
            }
            if (parameters.rawSize != null && parameters.rawSize.x > 0 && parameters.rawSize.y > 0) {
                int cropWidth = Math.min(parameters.rawSize.x, parameters.rawSize.y * 4 / 3) & ~1;
                int cropHeight = Math.min(parameters.rawSize.y, parameters.rawSize.x * 3 / 4) & ~1;
                cropScaleX = (float) cropWidth / parameters.rawSize.x;
                cropScaleY = (float) cropHeight / parameters.rawSize.y;
            }
        }
        CpuGainCache cache = CPU_GAIN_CACHE.get();
        boolean gainChanged = cache.map != gainMap || cache.mapWidth != gainWidth
                || cache.mapHeight != gainHeight || cache.frameWidth != width
                || cache.frameHeight != height || cache.cropScaleX != cropScaleX
                || cache.cropScaleY != cropScaleY;
        float[] gainMapUpdate = gainChanged ? gainMap : null;
        if (gainChanged) {
            cache.map = gainMap;
            cache.mapWidth = gainWidth;
            cache.mapHeight = gainHeight;
            cache.frameWidth = width;
            cache.frameHeight = height;
            cache.cropScaleX = cropScaleX;
            cache.cropScaleY = cropScaleY;
        }
        postProcessCpuNative(pixels, toneCurve, width, height, sensor, output, neutral,
                whiteScale, gainMapUpdate, gainWidth, gainHeight, cropScaleX, cropScaleY,
                (float) PhotonCamera.getSettings().saturation,
                (float) PhotonCamera.getSettings().contrastMpy,
                (float) PhotonCamera.getSettings().shadows);
        pixels.position(0);
    }

    private static float[] identityMatrix() {
        return new float[]{1.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f, 0.f, 1.f};
    }

    private static native void postProcessCpuNative(
            ByteBuffer pixels, ByteBuffer toneCurve, int width, int height,
            float[] sensorToIntermediate, float[] intermediateToSrgb,
            float[] neutralPoint, float[] whitePointScale, float[] gainMap,
            int gainWidth, int gainHeight, float cropScaleX, float cropScaleY,
            float saturation, float contrast, float shadows);

    private static float neutralGain(Rational[] neutral, int channel) {
        if (neutral == null || neutral.length <= channel || neutral[channel] == null) return 1.0f;
        return 1.0f / Math.max(neutral[channel].floatValue(), 1.0e-6f);
    }
}
