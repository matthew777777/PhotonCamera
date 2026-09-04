package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/** RawTherapee-style luminance Richardson-Lucy capture sharpening. */
public class ExperimentalCaptureSharpening extends Node {
    private static final int MAX_ITERATIONS = 10;

    @Tunable(title = "Radius", description = "Gaussian PSF sigma (px)", category = "Capture Sharpening", min = 0.1f, max = 2.0f, defaultValue = 0.75f, step = 0.05f)
    public float radius;
    @Tunable(title = "Corner Boost", description = "Additive radius increase at the corners", category = "Capture Sharpening", min = 0.0f, max = 2.0f, defaultValue = 0.0f, step = 0.05f)
    public float cornerBoost;
    @Tunable(title = "Contrast Threshold", description = "RT blend-mask threshold", category = "Capture Sharpening", min = 0.0f, max = 100.0f, defaultValue = 10.0f, step = 0.5f)
    public float contrastThreshold;
    @Tunable(title = "Iterations", description = "Number of RL iterations; zero is passthrough", category = "Capture Sharpening", min = 0.0f, max = 10.0f, defaultValue = 10.0f, step = 1.0f)
    public int iterations;
    @Tunable(title = "Debug Mask", description = "Show the sharpening mask", category = "Capture Sharpening", min = 0.0f, max = 1.0f, defaultValue = 0.0f, step = 1.0f)
    public int debugResponse;
    public float epsilon = 1e-4f;

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

        if (debugResponse == 1) {
            WorkingTexture = basePipeline.getMain();
            glProg.useAssetProgram("capturesharpen/capturesharpen");
            glProg.setTexture("OriginalBuffer", input);
            glProg.setVar("contrastThreshold", contrastThreshold);
            glProg.setVar("operation", 2);
            glProg.drawBlocks(WorkingTexture);
            glProg.closed = true;
            return;
        }

        // RT deconvolves the luminance plane, then applies its final blend to
        // the original RGB data.  Keeping this scalar estimate separate is
        // essential: blending inside each RL iteration is not equivalent.
        GLTexture estimate = new GLTexture(input.mSize, input.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        filter(input, estimate, 0, null);
        for (int iteration = 0; iteration < selectedIterations; iteration++) {
            GLTexture blurredEstimate = new GLTexture(input.mSize, input.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            filter(estimate, blurredEstimate, 0, null);
            GLTexture correction = new GLTexture(input.mSize, input.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            filter(input, correction, 1, blurredEstimate);
            blurredEstimate.close();

            glProg.useAssetProgram("capturesharpen/capturesharpen");
            glProg.setTexture("OriginalBuffer", input);
            glProg.setTexture("EstimateBuffer", estimate);
            glProg.setTexture("CorrectionBuffer", correction);
            glProg.setVar("operation", 0);

            GLTexture nextEstimate = new GLTexture(input.mSize, input.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            glProg.drawBlocks(nextEstimate);
            estimate.close();
            estimate = nextEstimate;
            correction.close();
        }

        WorkingTexture = basePipeline.getMain();
        glProg.useAssetProgram("capturesharpen/capturesharpen");
        glProg.setTexture("OriginalBuffer", input);
        glProg.setTexture("EstimateBuffer", estimate);
        glProg.setVar("contrastThreshold", contrastThreshold);
        glProg.setVar("operation", 1);
        glProg.drawBlocks(WorkingTexture);
        estimate.close();
        glProg.closed = true;
    }

    private void filter(GLTexture source, GLTexture target, int mode, GLTexture blurredEstimate) {
        glProg.useAssetProgram("capturesharpen/capturesharpenblur");
        glProg.setTexture("SourceBuffer", source);
        glProg.setVar("mode", mode);
        glProg.setVar("radius", radius);
        glProg.setVar("cornerBoost", cornerBoost);
        if (mode == 1) {
            glProg.setTexture("OriginalBuffer", source);
            glProg.setTexture("BlurredEstimate", blurredEstimate);
            glProg.setVar("epsilon", epsilon);
        }
        glProg.drawBlocks(target);
    }
}
