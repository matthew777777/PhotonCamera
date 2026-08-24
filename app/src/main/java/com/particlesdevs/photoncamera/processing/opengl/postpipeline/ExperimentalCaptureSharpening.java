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
        int selectedIterations = Math.max(0, Math.min(iterations, MAX_ITERATIONS));
        if (debugResponse == 0 && selectedIterations == 0) {
            WorkingTexture = input;
            glProg.closed = true;
            return;
        }

        GLTexture blurredOriginal = new GLTexture(input.mSize, input.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        filter(input, blurredOriginal, 0, null);

        if (debugResponse == 1) {
            WorkingTexture = basePipeline.getMain();
            runCombine(input, blurredOriginal, input, input, WorkingTexture, selectedIterations, selectedIterations, 1);
            blurredOriginal.close();
            glProg.closed = true;
            return;
        }

        GLTexture estimate = input;
        for (int iteration = 0; iteration < selectedIterations; iteration++) {
            GLTexture blurredEstimate = new GLTexture(input.mSize, input.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            filter(estimate, blurredEstimate, 0, null);
            GLTexture correction = new GLTexture(input.mSize, input.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            filter(input, correction, 1, blurredEstimate);
            blurredEstimate.close();

            GLTexture nextEstimate = new GLTexture(input.mSize, input.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            runCombine(input, blurredOriginal, estimate, correction, nextEstimate,
                    iteration, selectedIterations, 0);
            if (estimate != input) estimate.close();
            estimate = nextEstimate;
            correction.close();
        }

        WorkingTexture = estimate;
        blurredOriginal.close();
        glProg.closed = true;
    }

    private void filter(GLTexture source, GLTexture target, int mode, GLTexture blurredEstimate) {
        glProg.setLayout(8, 8, 1);
        glProg.useAssetProgram("demosaic/capturesharpen/capturesharpenblur", true);
        glProg.setTextureCompute("SourceBuffer", source, false);
        // Keep every declared image bound. The inactive inputs are ignored by
        // mode 0, while mode 1 reads the original and blurred estimate tiles.
        glProg.setTextureCompute("OriginalBuffer", source, false);
        glProg.setTextureCompute("BlurredEstimate", mode == 1 ? blurredEstimate : source, false);
        glProg.setTextureCompute("OutputBuffer", target, true);
        glProg.setVar("mode", mode);
        glProg.setVar("radius", radius);
        glProg.setVar("cornerBoost", cornerBoost);
        glProg.setVar("epsilon", epsilon);
        glProg.computeAuto(target.mSize, 1, false);
    }

    private void runCombine(GLTexture original, GLTexture blurredOriginal, GLTexture estimate,
                            GLTexture correction, GLTexture target, int iteration, int iterationCount,
                            int debug) {
        glProg.useAssetProgram("demosaic/capturesharpen/capturesharpen");
        glProg.setTexture("OriginalBuffer", original);
        glProg.setTexture("BlurredOriginal", blurredOriginal);
        glProg.setTexture("EstimateBuffer", estimate);
        glProg.setTexture("CorrectionBuffer", correction);
        glProg.setVar("contrastThreshold", contrastThreshold);
        glProg.setVar("iterationIndex", iteration);
        glProg.setVar("iterations", iterationCount);
        glProg.setVar("debugResponse", debug);
        glProg.setVar("minCorrection", minCorrection);
        glProg.setVar("maxCorrection", maxCorrection);
        glProg.setVar("maxOutput", maxOutput);
        glProg.drawBlocks(target);
    }
}
