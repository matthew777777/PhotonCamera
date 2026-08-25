package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import java.util.Arrays;

/**
 * A grid of RGB 3x4 affine transforms indexed by image x/y and guide luminance.
 *
 * <p>Coefficients are cell-major. Each cell stores the red, green and blue rows
 * consecutively: {@code [r0,r1,r2,rBias, g0,...,gBias, b0,...,bBias]}.</p>
 */
public final class BilateralGrid {
    public static final int ROWS = 3;
    public static final int COEFFICIENTS_PER_ROW = 4;
    public static final int COEFFICIENTS_PER_CELL = ROWS * COEFFICIENTS_PER_ROW;

    private final int width;
    private final int height;
    private final int depth;
    private final float[] coefficients;

    public BilateralGrid(int width, int height, int depth, float[] coefficients) {
        if (width < 1 || height < 1 || depth < 1) {
            throw new IllegalArgumentException("Grid dimensions must be positive");
        }
        long expected = (long) width * height * depth * COEFFICIENTS_PER_CELL;
        if (expected > Integer.MAX_VALUE || coefficients == null || coefficients.length != (int) expected) {
            throw new IllegalArgumentException("Expected " + expected + " coefficients");
        }
        for (float coefficient : coefficients) {
            if (!Float.isFinite(coefficient)) {
                throw new IllegalArgumentException("Grid coefficients must be finite");
            }
        }
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.coefficients = Arrays.copyOf(coefficients, coefficients.length);
    }

    public static BilateralGrid identity(int width, int height, int depth) {
        int cells = Math.multiplyExact(Math.multiplyExact(width, height), depth);
        float[] coefficients = new float[Math.multiplyExact(cells, COEFFICIENTS_PER_CELL)];
        for (int cell = 0; cell < cells; cell++) {
            int base = cell * COEFFICIENTS_PER_CELL;
            coefficients[base] = 1.0f;
            coefficients[base + 5] = 1.0f;
            coefficients[base + 10] = 1.0f;
        }
        return new BilateralGrid(width, height, depth, coefficients);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getDepth() {
        return depth;
    }

    public float getCoefficient(int x, int y, int z, int row, int column) {
        if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth
                || row < 0 || row >= ROWS || column < 0 || column >= COEFFICIENTS_PER_ROW) {
            throw new IndexOutOfBoundsException("Coefficient outside bilateral grid");
        }
        int cell = (z * height + y) * width + x;
        return coefficients[cell * COEFFICIENTS_PER_CELL + row * COEFFICIENTS_PER_ROW + column];
    }

    /** Returns one tightly packed RGBA texture containing an affine output row. */
    FloatBufferData row(int row) {
        if (row < 0 || row >= ROWS) {
            throw new IllegalArgumentException("Invalid affine row " + row);
        }
        int cells = width * height * depth;
        float[] packed = new float[cells * COEFFICIENTS_PER_ROW];
        for (int cell = 0; cell < cells; cell++) {
            System.arraycopy(coefficients, cell * COEFFICIENTS_PER_CELL + row * COEFFICIENTS_PER_ROW,
                    packed, cell * COEFFICIENTS_PER_ROW, COEFFICIENTS_PER_ROW);
        }
        return new FloatBufferData(packed);
    }

    /** Small wrapper keeps the mutable upload array package-private. */
    static final class FloatBufferData {
        final float[] values;

        FloatBufferData(float[] values) {
            this.values = values;
        }
    }
}
