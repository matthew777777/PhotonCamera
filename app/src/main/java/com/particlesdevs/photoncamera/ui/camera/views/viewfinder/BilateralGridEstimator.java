package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import java.nio.FloatBuffer;
import java.nio.ByteBuffer;

/** Thin JNI wrapper around the native fast local-fit BGU estimator. */
public final class BilateralGridEstimator {
    private static final boolean NATIVE_AVAILABLE;

    static {
        boolean loaded;
        try {
            System.loadLibrary("bgu");
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            loaded = false; // Local JVM tests do not include Android-ABI libraries.
        }
        NATIVE_AVAILABLE = loaded;
    }

    public static final class Options {
        public final int gridWidth;
        public final int gridHeight;
        public final int gridDepth;
        public final int blurPasses;
        public final float regularization;

        public Options(int gridWidth, int gridHeight, int gridDepth,
                       int blurPasses, float regularization) {
            if (gridWidth < 1 || gridHeight < 1 || gridDepth < 1) {
                throw new IllegalArgumentException("Grid dimensions must be positive");
            }
            long cells = (long) gridWidth * gridHeight * gridDepth;
            if (cells > Integer.MAX_VALUE / BilateralGrid.COEFFICIENTS_PER_CELL) {
                throw new IllegalArgumentException("Bilateral grid is too large");
            }
            if (blurPasses < 0 || !Float.isFinite(regularization) || regularization <= 0.0f) {
                throw new IllegalArgumentException("Invalid smoothing options");
            }
            this.gridWidth = gridWidth;
            this.gridHeight = gridHeight;
            this.gridDepth = gridDepth;
            this.blurPasses = blurPasses;
            this.regularization = regularization;
        }

        public static Options previewDefaults() {
            // A coarser spatial grid is intentionally used for a streamed
            // model. It is faster and prevents a one-frame-old fit from
            // attaching strong local color transforms to moving objects.
            return new Options(12, 9, 8, 2, 1.0e-3f);
        }
    }

    private final Options options;
    private Timing lastTiming = Timing.ZERO;

    public static final class Timing {
        static final Timing ZERO = new Timing(0, 0, 0, 0);
        public final long splatUs;
        public final long blurUs;
        public final long solveUs;
        public final long totalUs;

        Timing(long splatUs, long blurUs, long solveUs, long totalUs) {
            this.splatUs = splatUs;
            this.blurUs = blurUs;
            this.solveUs = solveUs;
            this.totalUs = totalUs;
        }

        static Timing fromNative(long[] values) {
            return values == null || values.length != 4 ? ZERO
                    : new Timing(values[0], values[1], values[2], values[3]);
        }
    }

    public BilateralGridEstimator(Options options) {
        if (options == null) throw new IllegalArgumentException("Options are required");
        this.options = options;
    }

    /** Compatibility overload. Native code may pin or copy these Java arrays. */
    public BilateralGrid estimate(float[] inputRgb, float[] targetRgb, int width, int height) {
        return estimate(inputRgb, targetRgb, null, width, height);
    }

    public BilateralGrid estimate(float[] inputRgb, float[] targetRgb, float[] guide,
                                  int width, int height) {
        validateDimensions(width, height);
        int pixels = Math.multiplyExact(width, height);
        int rgbValues = Math.multiplyExact(pixels, 3);
        if (inputRgb == null || targetRgb == null || inputRgb.length != rgbValues
                || targetRgb.length != rgbValues || (guide != null && guide.length != pixels)) {
            throw new IllegalArgumentException("Estimator buffers have the wrong size");
        }
        requireNative();
        return makeGrid(nativeEstimateArrays(inputRgb, targetRgb, guide, width, height,
                options.gridWidth, options.gridHeight, options.gridDepth,
                options.blurPasses, options.regularization));
    }

    /** Zero-copy runtime entry point using direct float buffers from element zero. */
    public BilateralGrid estimate(FloatBuffer inputRgb, FloatBuffer targetRgb,
                                  FloatBuffer guide, int width, int height) {
        validateDimensions(width, height);
        int pixels = Math.multiplyExact(width, height);
        int rgbValues = Math.multiplyExact(pixels, 3);
        if (inputRgb == null || targetRgb == null || !inputRgb.isDirect() || !targetRgb.isDirect()
                || inputRgb.capacity() < rgbValues || targetRgb.capacity() < rgbValues
                || (guide != null && (!guide.isDirect() || guide.capacity() < pixels))) {
            throw new IllegalArgumentException("Estimator requires sufficiently large direct buffers");
        }
        requireNative();
        return makeGrid(nativeEstimateDirect(inputRgb, targetRgb, guide, width, height,
                options.gridWidth, options.gridHeight, options.gridDepth,
                options.blurPasses, options.regularization));
    }

    /** Native conversion and estimation for direct, tightly packed RGBA8 buffers. */
    public BilateralGrid estimateRgba8(ByteBuffer inputRgba, ByteBuffer targetRgba,
                                       int width, int height) {
        validateDimensions(width, height);
        int bytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (inputRgba == null || targetRgba == null || !inputRgba.isDirect() || !targetRgba.isDirect()
                || inputRgba.capacity() < bytes || targetRgba.capacity() < bytes) {
            throw new IllegalArgumentException("Estimator requires direct RGBA8 buffers");
        }
        requireNative();
        BilateralGrid grid = makeGrid(nativeEstimateRgba8(inputRgba, targetRgba, width, height,
                options.gridWidth, options.gridHeight, options.gridDepth,
                options.blurPasses, options.regularization));
        lastTiming = Timing.fromNative(nativeGetLastTimingUs());
        return grid;
    }

    public Timing getLastTiming() {
        return lastTiming;
    }

    public BilateralGrid estimate(FloatBuffer inputRgb, FloatBuffer targetRgb,
                                  int width, int height) {
        return estimate(inputRgb, targetRgb, null, width, height);
    }

    static boolean isNativeAvailable() {
        return NATIVE_AVAILABLE;
    }

    private BilateralGrid makeGrid(float[] coefficients) {
        if (coefficients == null) throw new IllegalStateException("Native BGU estimation failed");
        return new BilateralGrid(options.gridWidth, options.gridHeight, options.gridDepth, coefficients);
    }

    private static void validateDimensions(int width, int height) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Image dimensions must be positive");
    }

    private static void requireNative() {
        if (!NATIVE_AVAILABLE) throw new IllegalStateException("Native BGU library is unavailable");
    }

    private static native float[] nativeEstimateArrays(
            float[] inputRgb, float[] targetRgb, float[] guide, int width, int height,
            int gridWidth, int gridHeight, int gridDepth, int blurPasses, float regularization);

    private static native float[] nativeEstimateDirect(
            FloatBuffer inputRgb, FloatBuffer targetRgb, FloatBuffer guide, int width, int height,
            int gridWidth, int gridHeight, int gridDepth, int blurPasses, float regularization);

    private static native float[] nativeEstimateRgba8(
            ByteBuffer inputRgba, ByteBuffer targetRgba, int width, int height,
            int gridWidth, int gridHeight, int gridDepth, int blurPasses, float regularization);

    private static native long[] nativeGetLastTimingUs();
}
