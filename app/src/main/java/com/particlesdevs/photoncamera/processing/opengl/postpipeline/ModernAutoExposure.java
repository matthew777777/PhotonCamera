package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.processing.opengl.scripts.GLHistogram;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;
import com.particlesdevs.photoncamera.util.Log;

public class ModernAutoExposure extends Node {
    private static final String TAG = "ModernAutoExposure";

    @Tunable(title = "Enable Modern AE", category = "Modern AE", defaultValue = 1, min = 0, max = 1, step = 1)
    boolean enable = true;

    @Tunable(title = "Target Midtone", category = "Modern AE", min = 0.0f, max = 1.0f, defaultValue = 0.18f)
    float targetMidtone = 0.18f;

    @Tunable(title = "Highlight Percentile", category = "Modern AE", min = 90.0f, max = 100.0f, defaultValue = 98.0f)
    float highlightPercentile = 98.0f;

    @Tunable(title = "Highlight Protection", category = "Modern AE", min = 0.0f, max = 1.0f, defaultValue = 0.5f)
    float highlightProtection = 0.5f;

    @Tunable(title = "Highlight Limit", category = "Modern AE", min = 0.5f, max = 5.0f, defaultValue = 1.5f)
    float highlightLimit = 1.5f;

    @Tunable(title = "Exposure Smoothing", category = "Modern AE", min = 0.0f, max = 0.99f, defaultValue = 0.0f)
    float exposureSmoothing = 0.0f;

    private static float lastMpy = -1.0f;

    @Tunable(title = "EV Min", category = "Modern AE", min = -10.0f, max = 0.0f, defaultValue = -8.0f)
    float evMin = -8.0f;

    @Tunable(title = "EV Max", category = "Modern AE", min = 0.0f, max = 10.0f, defaultValue = 8.0f)
    float evMax = 8.0f;

    public ModernAutoExposure() {
        super("", "ModernAutoExposure");
    }

    @Override
    public void Compile() {
    }

    @Override
    public void Run() {
        if (!enable) {
            WorkingTexture = previousNode.WorkingTexture;
            return;
        }

        // 1. Compute Log-Luminance Histogram
        GLHistogram histogram = new GLHistogram(glProg, 256);
        histogram.Custom = true;
        histogram.Rc = true;
        histogram.Gc = false;
        histogram.Bc = false;
        histogram.Ac = false;

        // Custom program snippet for log-luminance indexing
        histogram.CustomProgram = 
            "float lum = dot(texColor.rgb, vec3(0.299, 0.587, 0.114));" +
            "float ev = log2(lum + 1e-6);" +
            "float normEv = (ev - input1) / (input2 - input1);" +
            "texColorUint.r = uint(clamp(normEv * 255.0, 0.0, 255.0));";
        
        histogram.input1 = evMin;
        histogram.input2 = evMax;

        int[][] result = histogram.Compute(previousNode.WorkingTexture);
        int[] hist = result[0];
        
        // Explicitly clear histogram defines to avoid affecting subsequent fragment shaders
        glProg.setDefine("COL_CUSTOM", false);
        glProg.setDefine("CUSTOM_PROGRAM", "");

        // 2. Compute Statistics
        int totalPixels = 0;
        for (int count : hist) totalPixels += count;

        if (totalPixels == 0) {
            Log.e(TAG, "No pixels in histogram!");
            WorkingTexture = previousNode.WorkingTexture;
            return;
        }

        float medianEv = getPercentileEv(hist, totalPixels, 0.5f);
        float highEv = getPercentileEv(hist, totalPixels, highlightPercentile / 100.0f);
        float shadowEv = getPercentileEv(hist, totalPixels, 0.01f);

        // Saturated percentage (pixels at the last bin)
        float saturatedPercent = (float) hist[hist.length - 1] / (float) totalPixels * 100.0f;

        Log.d(TAG, String.format(java.util.Locale.US,
              "Stats - Total: %d, Median EV: %.2f, Highlight EV: %.2f, Shadow EV: %.2f, Saturated: %.2f%%",
              totalPixels, medianEv, highEv, shadowEv, saturatedPercent));

        // 3. Estimate Exposure Multiplier
        float medianLum = (float) Math.pow(2.0, medianEv);
        float mpy = targetMidtone / (medianLum + 1e-6f);

        // Highlight Headroom
        float highLum = (float) Math.pow(2.0, highEv);
        float headroom = highlightLimit / (highLum * mpy + 1e-6f);
        Log.d(TAG, String.format(java.util.Locale.US, "Highlight Headroom: %.2f stops", Math.log(headroom)/Math.log(2.0)));

        // Highlight Protection
        if (highLum * mpy > highlightLimit) {
            float reduction = highlightLimit / (highLum * mpy);
            // Blend based on highlightProtection strength
            float finalMpy = mpy * (1.0f - highlightProtection + highlightProtection * reduction);
            Log.d(TAG, String.format(java.util.Locale.US, "Highlight Protection: mpy %.6f -> %.6f", mpy, finalMpy));
            mpy = finalMpy;
        }

        // 3.1 Exposure Smoothing
        if (exposureSmoothing > 0.0f && lastMpy > 1e-4f) {
            mpy = lastMpy * exposureSmoothing + mpy * (1.0f - exposureSmoothing);
        }
        lastMpy = mpy;
        if (lastMpy < 1e-4f) lastMpy = -1.0f; // Reset if it collapses

        Log.d(TAG, String.format(java.util.Locale.US, "Final Exposure Multiplier: %.6f (EV Offset: %.2f)",
              mpy, Math.log(mpy)/Math.log(2.0)));

        // 4. Apply Exposure Scaling
        glProg.useAssetProgram("modern_autoexposure");
        glProg.setTexture("InputBuffer", previousNode.WorkingTexture);
        glProg.setVar("exposureScale", Math.max(mpy, 1e-6f));
        
        WorkingTexture = basePipeline.getMain();
        glProg.drawBlocks(WorkingTexture);
        glProg.closed = true;
    }

    private float getPercentileEv(int[] hist, int totalPixels, float percentile) {
        int threshold = (int) (totalPixels * percentile);
        int count = 0;
        for (int i = 0; i < hist.length; i++) {
            count += hist[i];
            if (count >= threshold) {
                float normIndex = (float) i / 255.0f;
                return normIndex * (evMax - evMin) + evMin;
            }
        }
        return evMax;
    }
}
