    package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

    import android.graphics.Point;
    import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
    import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
    import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
    import com.particlesdevs.photoncamera.processing.opengl.scripts.GLHistogram;
    import com.particlesdevs.photoncamera.settings.annotations.Tunable;
    import com.particlesdevs.photoncamera.util.BufferUtils;
    import com.particlesdevs.photoncamera.util.Log;
    import com.particlesdevs.photoncamera.util.Math2;

    import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
    import static android.opengl.GLES20.GL_LINEAR;

    /**
     * Curve-based auto exposure for the default tone pipeline.
     *
     * Runs before {@link Initial} on the linear input and estimates exposure
     * with the classic AutoExposure scheme (fill-coefficient weighted average
     * gain, noise/max clamps, Reinhard normalization, top-0.5% white point
     * search), plus an adaptive highlight shoulder: the image fraction that
     * would clip under the estimated response drives a soft knee that
     * compresses the top of the range toward 1.0 on HDR scenes instead of
     * blowing out to flat white. Instead of applying the gain in its own
     * full-res pass, it bakes the per-channel display response (gamma lift
     * -&gt; gain -&gt; extended Reinhard -&gt; gamma lift -&gt; shoulder) into a 1D
     * curve texture stored in {@link PostPipeline#exposureCurve}; Initial
     * samples that curve at the end of its shader, fusing the exposure pass
     * into Initial's draw.
     *
     * The histogram covers the linear input, so bins are mapped through the
     * sRGB OETF to keep the gain estimate in the display domain the curve
     * operates on.
     *
     * Renders nothing: passes the input texture through and marks the program
     * closed so the pipeline skips the draw for this node.
     */
    public class AutoExposureCurve extends Node {
        @Tunable(title = "Histogram size", category = "Auto Exposure", defaultValue = 256, min = 256, max = 16384, step = 16, description = "Histogram bin count")
        int histSize;

        @Tunable(title = "Target Brightness", category = "Auto Exposure", max = 255.0f, defaultValue = 128.0f)
        float target;

        @Tunable(title = "Noise Max", category = "Auto Exposure", max = 1.0f, defaultValue = 0.05f)
        float noiseMax;

        @Tunable(title = "Gain Max", category = "Auto Exposure", max = 20.0f, defaultValue = 9.0f)
        float gainMax;

        @Tunable(title = "Enable WhitePoint Search", category = "Auto Exposure", defaultValue = 1, min = 0, max = 1, step = 1, description = "Enable white point search for Reinhard tone mapping")
        boolean enableWP;

        @Tunable(title = "WhitePoint apply level", category = "Auto Exposure", min = 0.0f, max = 1.0f, step = 0.1f, defaultValue = 0.8f, description = "Lower level disables white point, higher level applies full")
        float whiteApply;

        @Tunable(title = "Fill coefficient", category = "Auto Exposure", min = 0.0f, max = 1.0f, step = 0.01f, defaultValue = 0.99f, description = "Lower fill ratio can skip right histogram value peaks for HDR scenarios")
        float fillCoefficient;

    @Tunable(title = "Apply gamma mix", category = "Auto Exposure", min = 0.0f, max = 1.0f, step = 0.01f, defaultValue = 0.1f, description = "Blend between AE color space sRGB-linear")
    float applyGammaMix;

    @Tunable(title = "Highlight Compression", category = "Auto Exposure", defaultValue = 1, min = 0, max = 1, step = 1, description = "Adaptive highlight shoulder: smoothly compress the top of the range on HDR scenes instead of clipping to flat white")
    boolean highlightCompression;

    @Tunable(title = "Highlight Knee Max", category = "Auto Exposure", min = 0.6f, max = 1.0f, step = 0.01f, defaultValue = 0.9f, description = "Shoulder start when almost nothing clips (higher = later rolloff)")
    float kneeMax;

    @Tunable(title = "Highlight Knee Min", category = "Auto Exposure", min = 0.3f, max = 1.0f, step = 0.01f, defaultValue = 0.55f, description = "Shoulder start at heavy clipping (lower = stronger highlight compression)")
    float kneeMin;

    @Tunable(title = "Highlight Clip Ref", category = "Auto Exposure", min = 0.01f, max = 0.5f, step = 0.01f, defaultValue = 0.1f, description = "Clipped image fraction at which the knee reaches its minimum")
    float kneeRef;

        private static final int CURVE_SIZE = 1024;

        public AutoExposureCurve() {
            super("", "AutoExposureCurve");
        }

        @Override
        public void AfterRun() {
        }

        @Override
        public void Compile() {}

        @Override
        public void Run() {
            int bins = histSize;
            if (bins < 16) bins = 256; // guard against a failed tunable injection

            GLHistogram histogram = new GLHistogram(glProg, bins);
            histogram.Rc = true;
            histogram.Gc = true;
            histogram.Bc = true;
            histogram.Ac = false;
            int[][] result;
            try {
                result = histogram.Compute(previousNode.WorkingTexture);
            } finally {
                histogram.close();
            }

            // Map the linear bins to the display domain (bin units) so the
            // gain math below estimates the display-referred exposure.
            float[] mapped = new float[bins];
            for (int i = 0; i < bins; i++) {
                mapped[i] = srgbEncode(i / (bins - 1.0f)) * (bins - 1.0f);
            }

            int histNormR = 0;
            int histNormG = 0;
            int histNormB = 0;
            for (int i = 0; i < bins; i++) {
                histNormR += result[0][i];
                histNormG += result[1][i];
                histNormB += result[2][i];
            }
            float sum = 0.0f;
            int cnt = 0;
            for (int i = 0; i < bins - 1; i++) {
                if (cnt > (histNormR + histNormG + histNormB) * fillCoefficient) {
                    Log.d(Name, "Histogram already full, coefficient:" + fillCoefficient);
                    break;
                }
                sum += (result[0][i] + result[1][i] + result[2][i]) * mapped[i];
                cnt += result[0][i] + result[1][i] + result[2][i];
            }
            float avg = cnt > 0 ? sum / cnt : (bins / 256.0f) * target;
            float mpy = (bins / 256.0f) * target / Math.max(avg, 1.0e-4f);

            sum = 0;
            int cnt2 = 0;
            float sumR = 0.0f;
            float sumG = 0.0f;
            float sumB = 0.0f;
            int cntR = 0;
            int cntG = 0;
            int cntB = 0;
            for (int i = bins - 1; i > Math.max(bins * 2.0 / 3.0, bins / (mpy + 0.001)); i--) {
                sum += Math.max(result[0][i] * mapped[i], Math.max(result[1][i] * mapped[i], result[2][i] * mapped[i]));
                sumR += result[0][i] * mapped[i];
                sumG += result[1][i] * mapped[i];
                sumB += result[2][i] * mapped[i];
                cntR += result[0][i];
                cntG += result[1][i];
                cntB += result[2][i];
                if (cntR > histNormR * 0.005f) {
                    cnt2 = cntR;
                    sum = sumR;
                    break;
                }
                if (cntG > histNormG * 0.005f) {
                    cnt2 = cntG;
                    sum = sumG;
                    break;
                }
                if (cntB > histNormB * 0.005f) {
                    cnt2 = cntB;
                    sum = sumB;
                    break;
                }
            }
            if (cnt2 == 0) {
                sum = bins - 1;
                cnt2 = 1;
            }
            float whiteMax = ((sum / cnt2) / bins);

            float gainNoiseMax = (float) (noiseMax / Math.sqrt(basePipeline.noiseS * 0.5 + basePipeline.noiseO));
            gainNoiseMax = Math.max(gainNoiseMax, 1.0f);
            if (mpy > gainNoiseMax) {
                Log.d(Name, "Clamping gain by noise from " + mpy + " to " + gainNoiseMax);
                mpy = gainNoiseMax;
            }
            if (mpy > gainMax) {
                Log.d(Name, "Clamping gain by max from " + mpy + " to " + gainMax);
                mpy = gainMax;
            }
            float normL = 0.0f;
            float normR = 0.0f;
            for (int i = 0; i < bins; i++) {
                float val = ((float) (i) / (bins - 1.0f)) * mpy;
                normL += Math.min(val, 1.0f);
                normR += (val * (1.0f + (val / (mpy * mpy)))) / (1.0f + val);
            }
            Log.d(Name, "Reinhard normalizer:" + normR + " normL:" + normL + " base Mpy:" + mpy);
            mpy *= normL / normR;

            whiteMax *= mpy;
            float whiteEff = enableWP ? Math2.mix(mpy, whiteMax, whiteApply) : mpy;
            Log.d(Name, "Reinhard white max (top 0.5%): " + whiteMax + " effective:" + whiteEff);
            Log.d(Name, "Average brightness: " + avg + ", multiplier: " + mpy);

            // Adaptive highlight shoulder: measure the image fraction whose
            // response lands above 1.0 under the estimated gain/white point -
            // exactly the highlights that would hard-clip to flat white on HDR
            // scenes. The more energy sits there, the lower the knee, so the
            // top of the curve rolls off smoothly toward (never reaching) 1.0.
            float knee = 1.0f;
            if (highlightCompression) {
                float kneeLo = Math.min(kneeMin, kneeMax);
                long clipped = 0;
                long totalCnt = (long) histNormR + histNormG + histNormB;
                for (int i = 0; i < bins; i++) {
                    float x = mapped[i] / (bins - 1.0f);
                    float g = Math2.mix(x, (float) Math.sqrt(x), applyGammaMix);
                    float v = g * mpy;
                    float r = v * (1.0f + v / (whiteEff * whiteEff)) / (1.0f + v);
                    if (r > 1.0f) clipped += (long) result[0][i] + result[1][i] + result[2][i];
                }
                float clippedFrac = totalCnt > 0 ? clipped / (float) totalCnt : 0.0f;
                knee = Math2.mix(kneeMax, kneeLo, Math.min(clippedFrac / Math.max(kneeRef, 1.0e-4f), 1.0f));
                Log.d(Name, "Highlight shoulder: clipped:" + clippedFrac + " knee:" + knee);
            }

            // Bake the per-channel exposure response into a 1D curve over the
            // display-encoded [0,1] range.
            float[] curve = new float[CURVE_SIZE];
            for (int i = 0; i < CURVE_SIZE; i++) {
                float x = i / (CURVE_SIZE - 1.0f);
                float g = Math2.mix(x, (float) Math.sqrt(x), applyGammaMix);
                float r = g * mpy;
                r = r * (1.0f + r / (whiteEff * whiteEff)) / (1.0f + r);
                float o = Math2.mix(r, r * r, applyGammaMix);
                if (knee < 1.0f) o = softShoulder(o, knee);
                curve[i] = Math.min(Math.max(o, 0.0f), 1.0f);
            }

            ((PostPipeline) basePipeline).exposureCurve = new GLTexture(new Point(CURVE_SIZE, 1),
                    new GLFormat(GLFormat.DataType.FLOAT_16), BufferUtils.getFrom(curve),
                    GL_LINEAR, GL_CLAMP_TO_EDGE);

            WorkingTexture = previousNode.WorkingTexture;
            glProg.closed = true;
        }

        /** sRGB OETF (linear [0,1] -> display encoded). */
        private static float srgbEncode(float v) {
            v = Math.min(Math.max(v, 0.0f), 1.0f);
            return v <= 0.0031308f ? v * 12.92f : 1.055f * (float) Math.pow(v, 1.0f / 2.4f) - 0.055f;
        }

        /**
         * C1 highlight shoulder: identity below the knee, smooth rational
         * rolloff with a horizontal asymptote at 1.0 above it (same shape as
         * softClip in initial.glsl). Monotone, so compressed highlights keep
         * their relative ordering and detail.
         */
        private static float softShoulder(float x, float knee) {
            if (x <= knee) return x;
            float t = x - knee;
            float s = 1.0f - knee;
            return knee + s * t / (t + s);
        }
    }
