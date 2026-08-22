package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * ExperimentalCaptureSharpening
 *
 * GLSL port of RawTherapee's Capture Sharpening: Richardson-Lucy
 * deconvolution with a Gaussian PSF, run immediately after demosaicing on
 * linear (pre-white-balance, pre-tone-curve) data. Source algorithm:
 * rtengine/capturesharpening.cc, GPL-3.0-or-later, Ingo Weyrich
 * "heckflosse" - https://rawpedia.rawtherapee.com/Capture_Sharpening.
 */
public class ExperimentalCaptureSharpening extends Node {

    @Tunable(
        title = "Radius",
        description = "Gaussian PSF sigma (px). RT default 0.75",
        category = "Capture Sharpening",
        subTab = "Experimental Pipeline",
        min = 0.1f,
        max = 2.0f,
        defaultValue = 0.75f,
        step = 0.05f
    )
    public float radius = 0.75f;

    @Tunable(
        title = "Corner Boost",
        description = "Increase radius in corners. RT default 0.0",
        category = "Capture Sharpening",
        subTab = "Experimental Pipeline",
        min = 0.0f,
        max = 2.0f,
        defaultValue = 0.0f,
        step = 0.05f
    )
    public float cornerBoost = 0.0f;

    @Tunable(
        title = "Contrast Threshold",
        description = "RT default 10.0",
        category = "Capture Sharpening",
        subTab = "Experimental Pipeline",
        min = 0.0f,
        max = 100.0f,
        defaultValue = 10.0f,
        step = 0.5f
    )
    public float contrastThreshold = 10.0f;

    @Tunable(
        title = "Iterations",
        description = "Number of RL iterations. RT default 1",
        category = "Capture Sharpening",
        subTab = "Experimental Pipeline",
        min = 1.0f,
        max = 10.0f,
        defaultValue = 1.0f,
        step = 1.0f
    )
    public int iterations = 1;

    @Tunable(
        title = "Debug Mask",
        description = "Show contrast mask instead of sharpened image",
        category = "Capture Sharpening",
        subTab = "Experimental Pipeline",
        min = 0.0f,
        max = 1.0f,
        defaultValue = 0.0f,
        step = 1.0f
    )
    public int debugResponse = 0;

    @Tunable(
        title = "Max Sigma",
        description = "Ceiling for sigma (RT 7-tap limit is 1.15)",
        category = "Capture Sharpening",
        subTab = "Experimental Pipeline",
        min = 0.5f,
        max = 2.0f,
        defaultValue = 1.15f,
        step = 0.05f
    )
    public float maxSigma = 1.15f;

    @Tunable(
        title = "Min Correction",
        description = "Per-iteration clamp floor. RT default 0.25",
        category = "Capture Sharpening",
        subTab = "Experimental Pipeline",
        min = 0.0f,
        max = 1.0f,
        defaultValue = 0.25f,
        step = 0.05f
    )
    public float minCorrection = 0.25f;

    @Tunable(
        title = "Max Correction",
        description = "Per-iteration clamp ceiling. RT default 4.0",
        category = "Capture Sharpening",
        subTab = "Experimental Pipeline",
        min = 1.0f,
        max = 10.0f,
        defaultValue = 4.0f,
        step = 0.1f
    )
    public float maxCorrection = 4.0f;

    public float epsilon = 1e-4f;
    public float maxOutput = 1.0f;

    public ExperimentalCaptureSharpening() {
        super("", "ExperimentalCaptureSharpening");
    }

    @Override
    public void Compile() {}

    @Override
    public void Run() {
        GLTexture input = previousNode.WorkingTexture;
        
        // Pass 1: BlurredOriginal (separable Gaussian)
        GLTexture blurredOriginalH = new GLTexture(input.mSize, input.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        blur(input, blurredOriginalH, true);
        GLTexture blurredOriginal = new GLTexture(input.mSize, input.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        blur(blurredOriginalH, blurredOriginal, false);
        blurredOriginalH.close();

        // Pass 2: Mask
        glProg.useAssetProgram("demosaic/capturesharpen/capturesharpenmask");
        glProg.setTexture("OriginalBuffer", input);
        glProg.setTexture("BlurredOriginal", blurredOriginal);
        glProg.setVar("contrastThreshold", contrastThreshold);
        GLTexture mask = new GLTexture(input.mSize, new GLFormat(GLFormat.DataType.FLOAT_16, 1), null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        glProg.drawBlocks(mask);
        blurredOriginal.close();

        if (debugResponse == 1) {
            WorkingTexture = basePipeline.getMain();
            glProg.useAssetProgram("demosaic/capturesharpen/capturesharpenupdate");
            glProg.setVar("debugResponse", 1);
            glProg.setTexture("MaskBuffer", mask);
            glProg.drawBlocks(WorkingTexture);
            mask.close();
            glProg.closed = true;
            return;
        }

        GLTexture estimate = input;
        for (int i = 0; i < iterations; i++) {
            // BlurredEstimate
            GLTexture blurredEstimateH = new GLTexture(input.mSize, input.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            blur(estimate, blurredEstimateH, true);
            GLTexture blurredEstimate = new GLTexture(input.mSize, input.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            blur(blurredEstimateH, blurredEstimate, false);
            blurredEstimateH.close();

            // Ratio
            glProg.useAssetProgram("demosaic/capturesharpen/capturesharpenratio");
            glProg.setTexture("OriginalBuffer", input);
            glProg.setTexture("BlurredEstimate", blurredEstimate);
            glProg.setTexture("MaskBuffer", mask);
            glProg.setVar("epsilon", epsilon);
            GLTexture ratio = new GLTexture(input.mSize, input.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            glProg.drawBlocks(ratio);
            blurredEstimate.close();

            // Correction (blur the ratio)
            GLTexture correctionH = new GLTexture(input.mSize, input.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            blur(ratio, correctionH, true);
            GLTexture correction = new GLTexture(input.mSize, input.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            blur(correctionH, correction, false);
            ratio.close();
            correctionH.close();

            // Update
            glProg.useAssetProgram("demosaic/capturesharpen/capturesharpenupdate");
            glProg.setTexture("EstimateBuffer", estimate);
            glProg.setTexture("CorrectionBuffer", correction);
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
        mask.close();
        glProg.closed = true;
    }

    private void blur(GLTexture source, GLTexture target, boolean horizontal) {
        glProg.useAssetProgram("demosaic/capturesharpen/capturesharpenblur");
        glProg.setTexture("SourceBuffer", source);
        glProg.setVar("direction", horizontal ? 1f : 0f, horizontal ? 0f : 1f);
        glProg.setVar("radius", radius);
        glProg.setVar("cornerBoost", cornerBoost);
        glProg.setVar("maxSigma", maxSigma);
        glProg.drawBlocks(target);
    }
}
