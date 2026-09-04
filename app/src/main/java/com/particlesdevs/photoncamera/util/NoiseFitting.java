package com.particlesdevs.photoncamera.util;

import com.particlesdevs.photoncamera.util.Log;

import androidx.annotation.NonNull;

import java.util.List;

public class NoiseFitting {
    // Class to hold a pair of w and N values
    public static class DataPoint {
        double w;
        double N;

        public DataPoint(double w, double N) {
            this.w = w;
            this.N = N;
        }
    }

    // Class to hold the fitted parameters
    public static class NoiseParameters {
        public double S;
        public double O;
        public double error;

        NoiseParameters(double S, double O, double error) {
            this.S = S;
            this.O = O;
            this.error = error;
        }
        NoiseParameters(double S, double O) {
            this.S = S;
            this.O = O;
        }
        @NonNull
        @Override
        public String toString() {
            return "S: " + S + ", O: " + O + ", error: " + error;
        }
    }

    public static NoiseParameters findParameters(List<DataPoint> data) {
        if (data.size() < 2) {
            Log.e("CurveFitting", "Not enough data points to fit a curve");
            return new NoiseParameters(0, 0, 0);
        }

        // Step 1: Find S using all possible pairs and average the results
        double sumS = 0;
        int pairCount = 0;

        for (int i = 0; i < data.size(); i++) {
            for (int j = i + 1; j < data.size(); j++) {
                DataPoint p1 = data.get(i);
                DataPoint p2 = data.get(j);

                double wDiff = p1.w - p2.w;
                if (Math.abs(wDiff) < 1e-10) continue; // Skip if w values are too close

                // N1^2 - N2^2 = (w1-w2)*S
                // Therefore S = (N1^2 - N2^2)/(w1-w2)
                double S = (p1.N * p1.N - p2.N * p2.N) / wDiff;
                sumS += S;
                pairCount++;
            }
        }

        if (pairCount == 0) {
            throw new IllegalArgumentException("No valid pairs found to calculate S");
        }

        double S = sumS / pairCount;

        // Step 2: Find O by averaging O values calculated from each point
        double sumO = 0;
        for (DataPoint p : data) {
            // N^2 = w*S + O
            // Therefore O = N^2 - w*S
            double O = p.N * p.N - p.w * S;
            sumO += O;
        }
        double O = sumO / data.size();

        double error = calculateRMSE(data, new NoiseParameters(S, O));

        return new NoiseParameters(S, O, error);
    }

    /**
     * Calculate the root mean squared error for the fitted parameters
     */
    public static double calculateRMSE(List<DataPoint> data, NoiseParameters params) {
        double sumSquaredError = 0;
        for (DataPoint p : data) {
            double predicted = Math.sqrt(Math.max(p.w * params.S + params.O,1e-10));
            double error = predicted - p.N;
            sumSquaredError += error * error;
        }
        return Math.sqrt(sumSquaredError / data.size());
    }
}