package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import java.nio.ByteBuffer;

/**
 * An immutable, display-ready RGBA8 frame produced from a RAW_SENSOR image.
 * Owns a pooled producer buffer without copying. Consumers must treat it as
 * read-only and close the frame when the estimator no longer needs it.
 */
public final class RawPreviewFrame implements AutoCloseable {
    private final int width;
    private final int height;
    private final ByteBuffer rgba;
    private final long timestampNs;
    private final Runnable release;
    private boolean closed;

    public RawPreviewFrame(int width, int height, ByteBuffer rgba, long timestampNs,
                           Runnable release) {
        if (width < 1 || height < 1 || rgba == null
                || rgba.capacity() < width * height * 4) {
            throw new IllegalArgumentException("Invalid RAW preview frame");
        }
        this.width = width;
        this.height = height;
        this.rgba = rgba;
        this.timestampNs = timestampNs;
        this.release = release;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public long getTimestampNs() { return timestampNs; }
    ByteBuffer pixels() { return rgba; }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            if (release != null) release.run();
        }
    }
}
