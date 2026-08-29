package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.opengl.GLES31;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;

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
    @Tunable(title = "Iteration Check", description = "RT early stop when a tile begins to undershoot", category = "Capture Sharpening", min = 0.0f, max = 1.0f, defaultValue = 1.0f, step = 1.0f)
    public int iterationCheck;
    @Tunable(title = "Debug Mask", description = "Show the sharpening mask", category = "Capture Sharpening", min = 0.0f, max = 1.0f, defaultValue = 0.0f, step = 1.0f)
    public int debugResponse;
    // RT uses 1e-5 in its 0..65535 working range; Photon uses normalized RGB.
    public float epsilon = 1e-5f / 65535.0f;

    public ExperimentalCaptureSharpening() { super("", "ExperimentalCaptureSharpening"); }
    @Override public void Compile() {}

    @Override
    public void Run() {
        GLTexture input = previousNode.WorkingTexture;
        int selectedIterations = Math.max(0, Math.min(iterations, MAX_ITERATIONS));
        if (debugResponse == 0 && selectedIterations == 0) {
            WorkingTexture = input;
            releaseRawTexture();
            glProg.closed = true;
            return;
        }

        GLTexture blendMask = buildBlendMask(input);
        if (debugResponse == 1) {
            WorkingTexture = basePipeline.getMain();
            glProg.useAssetProgram("capturesharpen/capturesharpenmaskdebug");
            glProg.setTexture("MaskBuffer", blendMask);
            glProg.drawBlocks(WorkingTexture);
            blendMask.close();
            glProg.closed = true;
            return;
        }

        boolean variableRadius = cornerBoost != 0.0f;
        int maxKernelRadius = variableRadius ? 6 : kernelRadius(radius);
        int minKernelRadius = variableRadius ? 2 : maxKernelRadius;
        int border = (!variableRadius && maxKernelRadius <= 3) ? 5 : 8;
        glProg.setDefine("MAX_KERNEL_RADIUS", maxKernelRadius);
        glProg.setDefine("MIN_KERNEL_RADIUS", minKernelRadius);
        glProg.setDefine("TILE_BORDER", border);
        glProg.setDefine("VARIABLE_RADIUS", variableRadius);
        glProg.useAssetProgram("capturesharpen/capturesharpeniterate", true);
        glProg.setTexture("OriginalBuffer", input);
        glProg.setTexture("BlendBuffer", blendMask);
        WorkingTexture = basePipeline.getMain();
        glProg.setTextureCompute("OutputBuffer", WorkingTexture, true);
        glProg.setVar("size", input.mSize.x, input.mSize.y);
        glProg.setVar("radius", radius);
        glProg.setVar("cornerBoost", cornerBoost);
        glProg.setVar("epsilon", epsilon);
        glProg.setVar("iterations", selectedIterations);
        glProg.setVar("iterationCheck", iterationCheck);
        GLES31.glDispatchCompute((input.mSize.x + 31) / 32,
                (input.mSize.y + 31) / 32, 1);
        GLES31.glMemoryBarrier(GLES31.GL_ALL_BARRIER_BITS);
        GLES31.glFinish();
        blendMask.close();
        glProg.closed = true;
    }

    private GLTexture buildBlendMask(GLTexture input) {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        if (pipeline.captureSharpeningRaw == null) {
            throw new IllegalStateException("Raw CFA texture is required for RawTherapee capture sharpening");
        }

        GLFormat maskFormat = new GLFormat(GLFormat.DataType.FLOAT_32, 1);
        GLTexture unsmoothed = new GLTexture(input.mSize, maskFormat);
        glProg.setDefine("RGB_RAW", basePipeline.mSettings.alignAlgorithm == 2);
        glProg.setDefine("MONO_RAW", basePipeline.mParameters.cfaPattern == 4);
        glProg.useAssetProgram("capturesharpen/capturesharpenmask", true);
        glProg.setTexture("OriginalBuffer", input);
        glProg.setTexture("RawBuffer", pipeline.captureSharpeningRaw);
        glProg.setVar("size", input.mSize.x, input.mSize.y);
        glProg.setVar("rawClip", pipeline.captureSharpeningClip);
        // RT's UI percentage is converted to its internal [0,1] threshold.
        glProg.setVar("contrastThreshold", contrastThreshold * 0.01f);
        glProg.setTextureCompute("OutputBuffer", unsmoothed, true);
        GLES31.glDispatchCompute((input.mSize.x + 7) / 8,
                (input.mSize.y + 7) / 8, 1);
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                | GLES31.GL_TEXTURE_FETCH_BARRIER_BIT);
        releaseRawTexture();

        // buildBlendMask intentionally skips smoothing when threshold is zero.
        if (contrastThreshold <= 0.0f) return unsmoothed;

        GLTexture horizontal = new GLTexture(input.mSize, maskFormat);
        runMaskBlur(unsmoothed, horizontal, true);
        unsmoothed.close();
        GLTexture vertical = new GLTexture(input.mSize, maskFormat);
        runMaskBlur(horizontal, vertical, false);
        horizontal.close();
        return vertical;
    }

    private void runMaskBlur(GLTexture source, GLTexture target, boolean horizontal) {
        glProg.setDefine("HORIZONTAL", horizontal);
        glProg.useAssetProgram("capturesharpen/capturesharpenmaskblur", true);
        glProg.setTexture("InputBuffer", source);
        glProg.setVar("size", source.mSize.x, source.mSize.y);
        glProg.setTextureCompute("OutputBuffer", target, GLES31.GL_READ_WRITE);
        int lineCount = horizontal ? source.mSize.y : source.mSize.x;
        GLES31.glDispatchCompute((lineCount + 63) / 64, 1, 1);
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                | GLES31.GL_TEXTURE_FETCH_BARRIER_BIT);
    }

    private void releaseRawTexture() {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        if (pipeline.captureSharpeningRaw != null) {
            pipeline.captureSharpeningRaw.close();
            pipeline.captureSharpeningRaw = null;
        }
    }

    private int kernelRadius(float sigma) {
        if (sigma < 0.6f) return 1;
        if (sigma <= 0.84f) return 2;
        if (sigma <= 1.15f) return 3;
        if (sigma <= 1.5f) return 4;
        return 6;
    }
}
