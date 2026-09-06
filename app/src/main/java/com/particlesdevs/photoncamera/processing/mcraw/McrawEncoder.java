package com.particlesdevs.photoncamera.processing.mcraw;

import java.io.IOException;
import java.nio.ByteBuffer;

public final class McrawEncoder {
    static { System.loadLibrary("mcrawEncoder"); }
    private McrawEncoder() {}
    public static native int encode(ByteBuffer input, int size, int width, int height,
                                    int stride, boolean raw10, int top, int cropHeight,
                                    boolean bin, ByteBuffer output) throws IOException;
}
