package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import org.junit.Test;
import org.junit.Assume;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class BilateralGridEstimatorTest {
    @Test
    public void recoversKnownGlobalAffineColorTransform() {
        Assume.assumeTrue(BilateralGridEstimator.isNativeAvailable());
        int width = 32;
        int height = 24;
        float[] input = variedImage(width, height);
        float[] target = new float[input.length];
        float[][] expected = {
                {0.8f, 0.1f, 0.0f, 0.03f},
                {0.0f, 1.1f, 0.05f, -0.02f},
                {0.1f, 0.0f, 0.7f, 0.08f}
        };
        for (int pixel = 0; pixel < width * height; pixel++) {
            int i = pixel * 3;
            for (int row = 0; row < 3; row++) {
                target[i + row] = expected[row][0] * input[i]
                        + expected[row][1] * input[i + 1]
                        + expected[row][2] * input[i + 2] + expected[row][3];
            }
        }

        BilateralGridEstimator estimator = new BilateralGridEstimator(
                new BilateralGridEstimator.Options(1, 1, 1, 0, 1.0e-6f));
        BilateralGrid grid = estimator.estimate(input, target, width, height);

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 4; column++) {
                assertEquals(expected[row][column], grid.getCoefficient(0, 0, 0, row, column), 2.0e-4f);
            }
        }
    }

    @Test
    public void emptyRangeCellsRemainFiniteAndNearIdentity() {
        Assume.assumeTrue(BilateralGridEstimator.isNativeAvailable());
        int width = 16;
        int height = 12;
        float[] input = new float[width * height * 3];
        float[] target = new float[input.length];
        for (int i = 0; i < input.length; i++) input[i] = target[i] = 0.25f;

        BilateralGrid grid = new BilateralGridEstimator(
                new BilateralGridEstimator.Options(4, 3, 8, 1, 1.0e-4f))
                .estimate(input, target, width, height);

        // Far guide slices receive no data after one blur pass and deliberately fall back.
        assertEquals(1.0f, grid.getCoefficient(0, 0, 7, 0, 0), 0.0f);
        assertEquals(1.0f, grid.getCoefficient(0, 0, 7, 1, 1), 0.0f);
        assertEquals(1.0f, grid.getCoefficient(0, 0, 7, 2, 2), 0.0f);
    }

    @Test
    public void rejectsInvalidBuffersAndGuideValues() {
        BilateralGridEstimator estimator = new BilateralGridEstimator(
                new BilateralGridEstimator.Options(1, 1, 1, 0, 1.0e-4f));
        assertThrows(IllegalArgumentException.class,
                () -> estimator.estimate(new float[3], new float[2], 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> estimator.estimate(new float[3], new float[3], new float[2], 1, 1));
    }

    private static float[] variedImage(int width, int height) {
        float[] image = new float[width * height * 3];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 3;
                image[i] = (float) x / (width - 1);
                image[i + 1] = (float) y / (height - 1);
                image[i + 2] = (float) ((x * 17 + y * 11) % 31) / 30.0f;
            }
        }
        return image;
    }
}
