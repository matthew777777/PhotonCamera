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
    private final ByteBuffer toneCurve;
    private final long timestampNs;
    private final Runnable release;
    /** White-balance gains (R, G, B) for the linear pixels; applied by StreamedPostPipeline. */
    private final float[] gains;
    /** Fully filled capture-style parameters for color correction; may be null. */
    private final com.particlesdevs.photoncamera.processing.render.Parameters parameters;
    private boolean closed;

    public RawPreviewFrame(int width, int height, ByteBuffer rgba, long timestampNs,
                           Runnable release, float[] gains, ByteBuffer toneCurve,
                           com.particlesdevs.photoncamera.processing.render.Parameters parameters) {
        if (width < 1 || height < 1 || rgba == null
                || rgba.capacity() < width * height * 4
                || toneCurve == null || toneCurve.capacity() < 256 * Float.BYTES
                || gains == null || gains.length < 3) {
            throw new IllegalArgumentException("Invalid RAW preview frame");
        }
        this.width = width;
        this.height = height;
        this.rgba = rgba;
        this.toneCurve = toneCurve;
        this.timestampNs = timestampNs;
        this.release = release;
        this.gains = gains;
        this.parameters = parameters;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public long getTimestampNs() { return timestampNs; }
    public float[] getGains() { return gains; }
    public ByteBuffer getToneCurve() { return toneCurve; }
    public com.particlesdevs.photoncamera.processing.render.Parameters getParameters() { return parameters; }
    ByteBuffer pixels() { return rgba; }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            if (release != null) release.run();
        }
    }
}
