package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.processing.opengl.scripts.GLHistogram;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;
import com.particlesdevs.photoncamera.util.Log;

public class ModernAutoExposure extends Node {
    private static final String TAG = "ModernAutoExposure";

    @Tunable(title = "Enable Modern AE", category = "Modern AE", subTab = "Experimental Pipeline", defaultValue = 1, min = 0, max = 1, step = 1)
    boolean enable = true;

    @Tunable(title = "Target Midtone", category = "Modern AE", subTab = "Experimental Pipeline", min = 0.0f, max = 1.0f, defaultValue = 0.10f)
    float targetMidtone = 0.10f;

    @Tunable(title = "Highlight Percentile", category = "Modern AE", subTab = "Experimental Pipeline", min = 90.0f, max = 100.0f, defaultValue = 99.0f)
    float highlightPercentile = 99.0f;

    @Tunable(title = "Highlight Protection", category = "Modern AE", subTab = "Experimental Pipeline", min = 0.0f, max = 1.0f, defaultValue = 0.70f)
    float highlightProtection = 0.70f;

    @Tunable(title = "Highlight Limit", category = "Modern AE", subTab = "Experimental Pipeline", min = 0.5f, max = 5.0f, defaultValue = 1.5f)
    float highlightLimit = 1.5f;

    @Tunable(title = "Exposure Smoothing", category = "Modern AE", subTab = "Experimental Pipeline", min = 0.0f, max = 0.99f, defaultValue = 0.0f)
    float exposureSmoothing = 0.0f;

    // Deliberately static: PostPipeline.BuildDefaultPipeline() constructs a brand
    // new ModernAutoExposure node for every capture, so an instance field could
    // never carry a value from one frame to the next - static is the only way
    // this smoothing feature can work at all. `volatile` gives basic cross-thread
    // visibility. The timestamp guard resets smoothing whenever there's been a
    // real gap (lens switch, new session, backgrounded app) so a stale value
    // from an unrelated previous session can't bleed into a new one.
    private static volatile float lastMpy = -1.0f;
    private static volatile long lastMpyTimeMs = 0L;
    private static final long SMOOTHING_STALE_MS = 2000L;

    @Tunable(title = "EV Min", category = "Modern AE", subTab = "Experimental Pipeline", min = -10.0f, max = 0.0f, defaultValue = -8.0f)
    float evMin = -8.0f;

    @Tunable(title = "EV Max", category = "Modern AE", subTab = "Experimental Pipeline", min = 0.0f, max = 10.0f, defaultValue = 8.0f)
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

        // Detect selected tonemapping method to anchor exposure correctly.
        // 0: ACES, 1: OpenDRT.
        int tonemapMethod = 0;
        try {
            tonemapMethod = androidx.preference.PreferenceManager.getDefaultSharedPreferences(com.particlesdevs.photoncamera.app.PhotonCamera.getSettingsManagerStatic().getContext())
                    .getInt("pref_tunable_moderntonemapping_tonemapmethod", 0);
        } catch (Exception e) {
            Log.w(TAG, "Failed to read tonemapMethod preference, defaulting to ACES");
        }

        float activeTargetMidtone;
        float activeHighlightLimit = highlightLimit;

        if (tonemapMethod == 1) {
            // OpenDRT expects 0.18 middle grey anchor.
            activeTargetMidtone = 0.18f;
        } else {
            // ACES has a brighter shoulder than OpenDRT. Keep its scene anchor
            // conservative and leave enough room below the display white point
            // for saturated skies and specular highlights.
            activeTargetMidtone = 0.10f;
            activeHighlightLimit = Math.min(activeHighlightLimit, 0.85f);
        }

        // GLProg already clears its Defines list right after every
        // useProgram()/useAssetProgram() call (see GLProg.useShader()), so
        // GLHistogram.Compute() below never actually leaves anything behind on
        // its own. This is a cheap, name-agnostic safety net for whatever ran
        // before this node - not a fix for a live leak.
        glProg.clearDefines();

        // 1. Compute Log-Luminance Histogram
        GLHistogram histogram = new GLHistogram(glProg, 256);
        histogram.Custom = true;
        histogram.Rc = true;
        histogram.Gc = false;
        histogram.Bc = false;
        histogram.Ac = false;

        // Custom program snippet for log-luminance indexing.
        // Rec.601 weights to match the luminocity() convention already used
        // throughout initial.glsl, rather than adding a second luma formula.
        histogram.CustomProgram =
            "float lum = dot(texColor.rgb, vec3(0.299, 0.587, 0.114));" +
            "float ev = log2(lum + 1e-6);" +
            "float normEv = (ev - input1) / (input2 - input1);" +
            "texColorUint.r = uint(clamp(normEv * 255.0, 0.0, 255.0));";

        histogram.input1 = evMin;
        histogram.input2 = evMax;

        int[][] result = histogram.Compute(previousNode.WorkingTexture);
        int[] hist = result[0];

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
        float compensation = PreferenceKeys.getModernExposureCompensation();
        float targetMidtoneCompensated = activeTargetMidtone *
            (float) Math.pow(2.0, compensation);

        float medianLum = (float) Math.pow(2.0, medianEv);
        float mpy = targetMidtoneCompensated / Math.max(medianLum, 1e-6f);

        // Highlight Headroom
        float highLum = (float) Math.pow(2.0, highEv);
        float headroom = activeHighlightLimit / (highLum * mpy + 1e-6f);
        Log.d(TAG, String.format(java.util.Locale.US, "Highlight Headroom: %.2f stops", Math.log(headroom) / Math.log(2.0)));

        // Highlight Protection
        if (highLum * mpy > activeHighlightLimit) {
            float reduction = activeHighlightLimit / (highLum * mpy);
            // Blend based on highlightProtection strength
            float finalMpy = mpy * (1.0f - highlightProtection + highlightProtection * reduction);
            Log.d(TAG, String.format(java.util.Locale.US, "Highlight Protection: mpy %.6f -> %.6f", mpy, finalMpy));
            mpy = finalMpy;
        }

        // 3.1 Bound the multiplier to the already-configured EV range so a
        // near-black or occluded frame can't drive it to an unbounded value
        // that blows out the instant real signal reappears.
        float minMpy = (float) Math.pow(2.0, evMin);
        float maxMpy = (float) Math.pow(2.0, evMax);
        mpy = Math.max(minMpy, Math.min(maxMpy, mpy));

        // 3.2 Exposure Smoothing - only blend against a recent, same-session value.
        long now = System.currentTimeMillis();
        boolean haveRecentMpy = lastMpy > 1e-4f && (now - lastMpyTimeMs) < SMOOTHING_STALE_MS;
        if (exposureSmoothing > 0.0f && haveRecentMpy) {
            mpy = lastMpy * exposureSmoothing + mpy * (1.0f - exposureSmoothing);
        }
        lastMpy = mpy;
        lastMpyTimeMs = now;
        if (lastMpy < 1e-4f) lastMpy = -1.0f; // Reset if it collapses

        Log.d(TAG, String.format(java.util.Locale.US, "Final Exposure Multiplier: %.6f (EV Offset: %.2f)",
              mpy, Math.log(mpy) / Math.log(2.0)));

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
