package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import java.util.Arrays;

/** An immutable, display-ready RGBA8 frame produced from a RAW_SENSOR image. */
public final class RawPreviewFrame {
    private final int width;
    private final int height;
    private final byte[] rgba;

    public RawPreviewFrame(int width, int height, byte[] rgba) {
        if (width < 1 || height < 1 || rgba == null || rgba.length != width * height * 4) {
            throw new IllegalArgumentException("Invalid RAW preview frame");
        }
        this.width = width;
        this.height = height;
        this.rgba = Arrays.copyOf(rgba, rgba.length);
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    byte[] pixels() { return rgba; }
}
