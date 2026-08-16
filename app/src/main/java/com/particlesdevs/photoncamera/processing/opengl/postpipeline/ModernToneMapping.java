package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.processing.opengl.scripts.GLHistogram;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;
import com.particlesdevs.photoncamera.util.Log;

import java.util.Locale;

public class ModernToneMapping extends Node {
    private static final String TAG = "ModernToneMapping";

    @Tunable(title = "Enable Tone Mapping", category = "Modern Tone", defaultValue = 1, min = 0, max = 1, step = 1)
    boolean enable = true;

    @Tunable(title = "Tone Mapping Strength", category = "Modern Tone", min = 0.1f, max = 2.0f, defaultValue = 1.0f)
    float strength = 1.0f;

    @Tunable(title = "Gamma", category = "Modern Tone", min = 1.0f, max = 3.0f, defaultValue = 2.2f)
    float gamma = 2.2f;

    @Tunable(title = "Debug Mode", category = "Modern Tone", defaultValue = 0, min = 0, max = 3, step = 1, description = "0=Normal, 1=ACES, 2=AgX, 3=Diff")
    int debugMode = 0;

    @Tunable(title = "AgX Exposure", category = "Modern Tone", min = -5.0f, max = 5.0f, defaultValue = 0.0f)
    float agxExposure = 0.0f;

    @Tunable(title = "AgX Look", category = "Modern Tone", min = 0.0f, max = 2.0f, defaultValue = 1.0f)
    float agxLook = 1.0f;

    @Tunable(title = "AgX Contrast", category = "Modern Tone", min = 0.5f, max = 2.0f, defaultValue = 1.0f)
    float agxContrast = 1.0f;

    @Tunable(title = "AgX Saturation", category = "Modern Tone", min = 0.0f, max = 2.0f, defaultValue = 1.0f)
    float agxSaturation = 1.0f;

    public ModernToneMapping() {
        super("", "ModernToneMapping");
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

        int globalMapper = com.particlesdevs.photoncamera.settings.PreferenceKeys.getModernToneMapper();

        // Numerical Diagnostics (Step 13)
        // We use a small histogram to estimate min/max/avg of the input
        runDiagnostics();
        
        glProg.useAssetProgram("modern_tonemapping");
        glProg.setTexture("InputBuffer", previousNode.WorkingTexture);
        glProg.setVar("strength", strength);
        glProg.setVar("gamma", gamma);
        
        // Pass selection and parameters
        glProg.setVar("toneMapper", globalMapper);
        glProg.setVar("debugMode", debugMode);
        glProg.setVar("agxExposure", (float) Math.pow(2.0, agxExposure));
        glProg.setVar("agxLook", agxLook);
        glProg.setVar("agxContrast", agxContrast);
        glProg.setVar("agxSaturation", agxSaturation);
        
        WorkingTexture = basePipeline.getMain();
        glProg.drawBlocks(WorkingTexture);
        glProg.closed = true;
        
        Log.d(TAG, "Run() - ToneMapper: " + (globalMapper == 0 ? "ACES" : "AgX") + ", DebugMode: " + debugMode);
    }

    private void runDiagnostics() {
        try {
            GLHistogram histogram = new GLHistogram(glProg, 256);
            histogram.Custom = true;
            histogram.Rc = true;
            histogram.CustomProgram = 
                "float lum = dot(texColor.rgb, vec3(0.299, 0.587, 0.114));" +
                "float ev = log2(lum + 1e-6);" +
                "float normEv = (ev - (-10.0)) / (10.0 - (-10.0));" + // Range -10 to 10 EV
                "texColorUint.r = uint(clamp(normEv * 255.0, 0.0, 255.0));";
            
            histogram.input1 = -10.0f;
            histogram.input2 = 10.0f;

            int[][] result = histogram.Compute(previousNode.WorkingTexture);
            int[] hist = result[0];
            
            // Clear defines
            glProg.setDefine("COL_CUSTOM", false);
            glProg.setDefine("CUSTOM_PROGRAM", "");

            int totalPixels = 0;
            float sumEv = 0;
            int minBin = 255;
            int maxBin = 0;
            for (int i = 0; i < hist.length; i++) {
                totalPixels += hist[i];
                if (hist[i] > 0) {
                    float ev = ((float) i / 255.0f) * 20.0f - 10.0f;
                    sumEv += ev * hist[i];
                    if (i < minBin) minBin = i;
                    if (i > maxBin) maxBin = i;
                }
            }

            if (totalPixels > 0) {
                float avgEv = sumEv / totalPixels;
                float minEv = ((float) minBin / 255.0f) * 20.0f - 10.0f;
                float maxEv = ((float) maxBin / 255.0f) * 20.0f - 10.0f;
                Log.d(TAG, String.format(Locale.US, "Input Stats - Min EV: %.2f, Max EV: %.2f, Avg EV: %.2f", minEv, maxEv, avgEv));
            }
        } catch (Exception e) {
            Log.e(TAG, "Diagnostics failed", e);
        }
    }
}
