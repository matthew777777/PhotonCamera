package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.params.BlackLevelPattern;
import android.media.Image;
import android.util.Rational;

import com.particlesdevs.photoncamera.ui.camera.views.viewfinder.RawPreviewFrame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Minimal RAW16 to RGBA8 path used to bring up the RAW viewfinder. */
final class RawPreviewProcessor {
    static final int OUTPUT_WIDTH = 512;
    static final int OUTPUT_HEIGHT = 384;

    private RawPreviewProcessor() {}

    static RawPreviewFrame process(Image image, int cfa, BlackLevelPattern blackPattern,
                                   int whiteLevel, Rational[] neutralColorPoint) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer raw = plane.getBuffer().duplicate().order(ByteOrder.nativeOrder());
        int sourceWidth = image.getWidth();
        int sourceHeight = image.getHeight();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
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

        byte[] out = new byte[OUTPUT_WIDTH * OUTPUT_HEIGHT * 4];
        for (int oy = 0; oy < OUTPUT_HEIGHT; oy++) {
            int sy = cropTop + (((oy * (cropHeight - 2)) / (OUTPUT_HEIGHT - 1)) & ~1);
            for (int ox = 0; ox < OUTPUT_WIDTH; ox++) {
                int sx = cropLeft + (((ox * (cropWidth - 2)) / (OUTPUT_WIDTH - 1)) & ~1);
                float p00 = normalized(raw, sx, sy, rowStride, pixelStride, black[0], whiteLevel);
                float p10 = normalized(raw, sx + 1, sy, rowStride, pixelStride, black[1], whiteLevel);
                float p01 = normalized(raw, sx, sy + 1, rowStride, pixelStride, black[2], whiteLevel);
                float p11 = normalized(raw, sx + 1, sy + 1, rowStride, pixelStride, black[3], whiteLevel);
                float r, g, b;
                switch (cfa) {
                    case 1: r = p10; g = (p00 + p11) * 0.5f; b = p01; break;
                    case 2: r = p01; g = (p00 + p11) * 0.5f; b = p10; break;
                    case 3: r = p11; g = (p10 + p01) * 0.5f; b = p00; break;
                    default: r = p00; g = (p10 + p01) * 0.5f; b = p11; break;
                }
                int dst = (oy * OUTPUT_WIDTH + ox) * 4;
                out[dst] = displayByte(r * gainR);
                out[dst + 1] = displayByte(g * gainG);
                out[dst + 2] = displayByte(b * gainB);
                out[dst + 3] = (byte) 255;
            }
        }
        return new RawPreviewFrame(OUTPUT_WIDTH, OUTPUT_HEIGHT, out);
    }

    private static float normalized(ByteBuffer raw, int x, int y, int rowStride,
                                    int pixelStride, float black, int white) {
        int sample = raw.getShort(y * rowStride + x * pixelStride) & 0xffff;
        return Math.max(0.0f, (sample - black) / Math.max(white - black, 1.0f));
    }

    private static float neutralGain(Rational[] neutral, int channel) {
        if (neutral == null || neutral.length <= channel || neutral[channel] == null) return 1.0f;
        return 1.0f / Math.max(neutral[channel].floatValue(), 1.0e-6f);
    }

    private static byte displayByte(float linear) {
        float srgb = (float) Math.pow(Math.max(0.0f, Math.min(linear, 1.0f)), 1.0 / 2.2);
        return (byte) Math.round(srgb * 255.0f);
    }
}
