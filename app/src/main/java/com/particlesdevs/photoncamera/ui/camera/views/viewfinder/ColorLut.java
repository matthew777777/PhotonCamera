package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

final class ColorLut {
    static final int SIZE = 17;
    final float[] rgb;
    ColorLut(float[] rgb) {
        if (rgb == null || rgb.length != SIZE * SIZE * SIZE * 3)
            throw new IllegalArgumentException("Invalid 17x17x17 RGB LUT");
        this.rgb = rgb;
    }
}
