package com.particlesdevs.photoncamera.processing;

import android.graphics.Bitmap;

public class JpegEncoder {
    static {
        System.loadLibrary("jpegEncoder");
    }

    public static boolean encodeJpeg(Bitmap bitmap, String path, int quality, boolean use444) {
        return nativeEncodeJpeg(bitmap, path, quality, use444);
    }

    private static native boolean nativeEncodeJpeg(Bitmap bitmap, String path, int quality, boolean use444);
}
