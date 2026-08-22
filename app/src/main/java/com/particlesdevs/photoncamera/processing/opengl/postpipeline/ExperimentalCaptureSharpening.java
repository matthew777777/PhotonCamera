package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/** RawTherapee-style luminance Richardson-Lucy capture sharpening. */
public class ExperimentalCaptureSharpening extends Node {
    private static final int MAX_ITERATIONS = 10;

    @Tunable(title = "Radius", description = "Gaussian PSF sigma (px)", category = "Capture Sharpening", subTab = "Experimental Pipeline", min = 0.1f, max = 2.0f, defaultValue = 0.75f, step = 0.05f)
    public float radius = 0.75f;
    @Tunable(title = "Corner Boost", description = "Additive radius increase at the corners", category = "Capture Sharpening", subTab = "Experimental Pipeline", min = 0.0f, max = 2.0f, defaultValue = 0.0f, step = 0.05f)
    public float cornerBoost = 0.0f;
    @Tunable(title = "Contrast Threshold", description = "RT blend-mask threshold", category = "Capture Sharpening", subTab = "Experimental Pipeline", min = 0.0f, max = 100.0f, defaultValue = 10.0f, step = 0.5f)
    public float contrastThreshold = 10.0f;
    @Tunable(title = "Iterations", description = "Number of RL iterations; zero is passthrough", category = "Capture Sharpening", subTab = "Experimental Pipeline", min = 0.0f, max = 10.0f, defaultValue = 1.0f, step = 1.0f)
    public int iterations = 1;
    @Tunable(title = "Debug Mask", description = "Show the sharpening mask", category = "Capture Sharpening", subTab = "Experimental Pipeline", min = 0.0f, max = 1.0f, defaultValue = 0.0f, step = 1.0f)
    public int debugResponse = 0;
    @Tunable(title = "Max Sigma", description = "Upper limit for the Gaussian radius", category = "Capture Sharpening", subTab = "Experimental Pipeline", min = 0.5f, max = 2.0f, defaultValue = 2.0f, step = 0.05f)
    public float maxSigma = 2.0f;
    @Tunable(title = "Min Correction", description = "Per-iteration correction floor", category = "Capture Sharpening", subTab = "Experimental Pipeline", min = 0.0f, max = 1.0f, defaultValue = 0.25f, step = 0.05f)
    public float minCorrection = 0.25f;
    @Tunable(title = "Max Correction", description = "Per-iteration correction ceiling", category = "Capture Sharpening", subTab = "Experimental Pipeline", min = 1.0f, max = 10.0f, defaultValue = 4.0f, step = 0.1f)
    public float maxCorrection = 4.0f;

    public float epsilon = 1e-4f;
    public float maxOutput = 1.0f;

    public ExperimentalCaptureSharpening() { super("", "ExperimentalCaptureSharpening"); }
    @Override public void Compile() {}

    @Override
    public void Run() {
        GLTexture input = previousNode.WorkingTexture;
        GLTexture blurredOriginalH = new GLTexture(input.mSize, input.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        blur(input, blurredOriginalH, true, 0, null, null, null);
        GLTexture blurredOriginal = new GLTexture(input.mSize, input.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        blur(blurredOriginalH, blurredOriginal, false, 0, null, null, null);
        blurredOriginalH.close();

        if (debugResponse == 1) {
            WorkingTexture = basePipeline.getMain();
            glProg.useAssetProgram("demosaic/capturesharpen/capturesharpen");
            glProg.setTexture("OriginalBuffer", input);
            glProg.setTexture("BlurredOriginal", blurredOriginal);
            glProg.setVar("contrastThreshold", contrastThreshold);
            glProg.setVar("debugResponse", 1);
            glProg.drawBlocks(WorkingTexture);
            blurredOriginal.close();
            glProg.closed = true;
            return;
        }

        int selectedIterations = Math.max(0, Math.min(iterations, MAX_ITERATIONS));
        GLTexture estimate = input;
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            GLTexture blurredEstimateH = new GLTexture(input.mSize, input.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            blur(estimate, blurredEstimateH, true, 0, null, null, null);
            GLTexture blurredEstimate = new GLTexture(input.mSize, input.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            blur(blurredEstimateH, blurredEstimate, false, 0, null, null, null);
            blurredEstimateH.close();

            GLTexture correctionH = new GLTexture(input.mSize, input.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            blur(input, correctionH, true, 1, blurredEstimate, blurredOriginal, null);
            GLTexture correction = new GLTexture(input.mSize, input.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            blur(input, correction, false, 1, blurredEstimate, blurredOriginal, correctionH);
            correctionH.close();
            blurredEstimate.close();

            glProg.useAssetProgram("demosaic/capturesharpen/capturesharpen");
            glProg.setTexture("OriginalBuffer", input);
            glProg.setTexture("BlurredOriginal", blurredOriginal);
            glProg.setTexture("EstimateBuffer", estimate);
            glProg.setTexture("CorrectionBuffer", correction);
            glProg.setVar("iterationIndex", iteration);
            glProg.setVar("iterations", selectedIterations);
            glProg.setVar("contrastThreshold", contrastThreshold);
            glProg.setVar("debugResponse", 0);
            glProg.setVar("minCorrection", minCorrection);
            glProg.setVar("maxCorrection", maxCorrection);
            glProg.setVar("maxOutput", maxOutput);

            GLTexture nextEstimate = basePipeline.getMain();
            glProg.drawBlocks(nextEstimate);
            estimate = nextEstimate;
            correction.close();
        }

        WorkingTexture = estimate;
        blurredOriginal.close();
        glProg.closed = true;
    }

    private void blur(GLTexture source, GLTexture target, boolean horizontal, int mode,
                      GLTexture blurredEstimate, GLTexture blurredOriginal, GLTexture previousPass) {
        glProg.useAssetProgram("demosaic/capturesharpen/capturesharpenblur");
        glProg.setTexture("SourceBuffer", previousPass == null ? source : previousPass);
        glProg.setVar("direction", horizontal ? 1f : 0f, horizontal ? 0f : 1f);
        glProg.setVar("mode", mode);
        glProg.setVar("radius", radius);
        glProg.setVar("cornerBoost", cornerBoost);
        glProg.setVar("maxSigma", Math.min(maxSigma, 2.0f));
        if (mode == 1) {
            glProg.setTexture("OriginalBuffer", source);
            glProg.setTexture("BlurredEstimate", blurredEstimate);
            glProg.setTexture("BlurredOriginal", blurredOriginal);
            glProg.setVar("contrastThreshold", contrastThreshold);
            glProg.setVar("epsilon", epsilon);
        }
        glProg.drawBlocks(target);
    }
}
