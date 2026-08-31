package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import java.nio.ByteBuffer;

final class ColorLutEstimator {
    static { System.loadLibrary("previewLut"); }
    private long lastTimeUs;
    ColorLut estimate(ByteBuffer input, ByteBuffer target, int width, int height) {
        ColorLut lut = new ColorLut(nativeEstimate(input, target, width, height));
        lastTimeUs = nativeGetLastTimeUs();
        return lut;
    }
    long getLastTimeUs() { return lastTimeUs; }
    private static native float[] nativeEstimate(ByteBuffer input, ByteBuffer target,
                                                  int width, int height);
    private static native long nativeGetLastTimeUs();
}
