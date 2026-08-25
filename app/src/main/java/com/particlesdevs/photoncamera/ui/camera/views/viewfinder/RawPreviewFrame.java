package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import java.nio.ByteBuffer;

/**
 * An immutable, display-ready RGBA8 frame produced from a RAW_SENSOR image.
 * Wraps the producer's buffer without copying; consumers must treat it as
 * read-only and expect the underlying buffer to be recycled by the producer.
 */
public final class RawPreviewFrame {
    private final int width;
    private final int height;
    private final ByteBuffer rgba;

    public RawPreviewFrame(int width, int height, ByteBuffer rgba) {
        if (width < 1 || height < 1 || rgba == null
                || rgba.capacity() < width * height * 4) {
            throw new IllegalArgumentException("Invalid RAW preview frame");
        }
        this.width = width;
        this.height = height;
        this.rgba = rgba;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    ByteBuffer pixels() { return rgba; }
}
