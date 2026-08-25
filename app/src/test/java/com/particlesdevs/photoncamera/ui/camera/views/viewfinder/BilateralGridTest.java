package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class BilateralGridTest {
    @Test
    public void identityHasExpectedAffineRows() {
        BilateralGrid grid = BilateralGrid.identity(2, 3, 4);

        assertEquals(2, grid.getWidth());
        assertEquals(3, grid.getHeight());
        assertEquals(4, grid.getDepth());
        assertArrayEquals(new float[] {1, 0, 0, 0}, firstRow(grid, 0), 0);
        assertArrayEquals(new float[] {0, 1, 0, 0}, firstRow(grid, 1), 0);
        assertArrayEquals(new float[] {0, 0, 1, 0}, firstRow(grid, 2), 0);
    }

    @Test
    public void rejectsWrongCoefficientCountAndNonFiniteValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new BilateralGrid(1, 1, 1, new float[11]));
        float[] values = new float[12];
        values[4] = Float.NaN;
        assertThrows(IllegalArgumentException.class,
                () -> new BilateralGrid(1, 1, 1, values));
    }

    @Test
    public void constructorDefensivelyCopiesCoefficients() {
        float[] values = new float[12];
        values[0] = 2;
        BilateralGrid grid = new BilateralGrid(1, 1, 1, values);
        values[0] = 9;

        assertEquals(2, grid.row(0).values[0], 0);
    }

    private static float[] firstRow(BilateralGrid grid, int row) {
        float[] values = grid.row(row).values;
        return new float[] {values[0], values[1], values[2], values[3]};
    }
}
