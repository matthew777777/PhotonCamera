package com.particlesdevs.photoncamera.processing.live;

import android.hardware.camera2.params.BlackLevelPattern;
import android.media.Image;
import android.util.Rational;

import com.particlesdevs.photoncamera.ui.camera.views.viewfinder.RawPreviewFrame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * JNI RAW16 to RGBA8 path for the RAW viewfinder. The native side samples the
 * direct camera buffer in place (honouring row/pixel strides) and writes the
 * gamma-encoded result into the pre-allocated direct output buffer below.
 */
public final class RawSuperPixel {
    public static final int OUTPUT_WIDTH = 512;
    public static final int OUTPUT_HEIGHT = 384;
    private static final int BUFFER_COUNT = 3;
    private static final ByteBuffer[] OUTPUTS = new ByteBuffer[BUFFER_COUNT];
    private static final boolean[] IN_USE = new boolean[BUFFER_COUNT];
    private static final Runnable[] RELEASERS = new Runnable[BUFFER_COUNT];

    static {
        System.loadLibrary("rawSuperPixel");
        for (int i = 0; i < BUFFER_COUNT; i++) {
            final int slot = i;
            OUTPUTS[i] = ByteBuffer.allocateDirect(OUTPUT_WIDTH * OUTPUT_HEIGHT * 4)
                    .order(ByteOrder.nativeOrder());
            RELEASERS[i] = () -> release(slot);
        }
    }

    private RawSuperPixel() {}

    public static RawPreviewFrame process(Image image, int cfa, BlackLevelPattern blackPattern,
                                          int whiteLevel, Rational[] neutralColorPoint) {
        int slot = acquire();
        if (slot < 0) return null;
        ByteBuffer output = OUTPUTS[slot];
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

        try {
            process(raw, output, plane.getRowStride(), plane.getPixelStride(),
                    cropLeft, cropTop, cropWidth, cropHeight, cfa, black, whiteLevel,
                    gainR, gainG, gainB);
            return new RawPreviewFrame(OUTPUT_WIDTH, OUTPUT_HEIGHT, output,
                    image.getTimestamp(), RELEASERS[slot]);
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

    private static native void process(ByteBuffer raw, ByteBuffer out,
                                       int rowStride, int pixelStride,
                                       int cropLeft, int cropTop, int cropWidth, int cropHeight,
                                       int cfa, float[] black, int whiteLevel,
                                       float gainR, float gainG, float gainB);

    private static float neutralGain(Rational[] neutral, int channel) {
        if (neutral == null || neutral.length <= channel || neutral[channel] == null) return 1.0f;
        return 1.0f / Math.max(neutral[channel].floatValue(), 1.0e-6f);
    }
}
