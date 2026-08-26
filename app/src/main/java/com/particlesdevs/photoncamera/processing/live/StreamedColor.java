package com.particlesdevs.photoncamera.processing.live;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.processing.render.ColorCorrectionTransform;
import com.particlesdevs.photoncamera.processing.render.Parameters;

/**
 * Compute-shader node of the streamed preview pipeline. Applies the same
 * matrix color correction chain as the capture pipeline's Initial
 * (initial.glsl applyColorSpace()): white point, sensor -> ProPhoto
 * intermediate, intermediate -> sRGB, followed by histogram-adapted AgX and
 * display encoding. Writes the
 * result via imageStore, so no fragment draw is needed after Run().
 */
public class StreamedColor extends Node {
    private static final float[] IDENTITY = new float[]{
            1.f, 0.f, 0.f,
            0.f, 1.f, 0.f,
            0.f, 0.f, 1.f};

    public StreamedColor() {
        super("preview/streamed_color", "StreamedColor");
    }

    @Override
    public void Compile() {
        glProg.useAssetProgram(Rid, true);
    }

    @Override
    public void Run() {
        StreamedPostPipeline pipeline = (StreamedPostPipeline) basePipeline;
        GLTexture input = previousNode != null ? previousNode.WorkingTexture : pipeline.inputTexture;
        Parameters params = basePipeline.mParameters;
        // Undo the native neutral-anchored encoding: the SuperPixel downsampler
        // stores each channel scaled by min(whitePoint)/whitePoint, so the
        // shader multiplies the ratios back to restore true brightness.
        // Unfilled white points (zeros) fall back to identity.
        float[] whitePointScale = new float[]{1.f, 1.f, 1.f};
        if (params != null && params.whitePoint != null && params.whitePoint.length == 3) {
            float minWhite = Float.MAX_VALUE;
            for (float value : params.whitePoint) if (value > 0.f && value < minWhite) minWhite = value;
            if (minWhite != Float.MAX_VALUE) {
                for (int i = 0; i < 3; i++)
                    whitePointScale[i] = params.whitePoint[i] > 0.f
                            ? params.whitePoint[i] / minWhite : 1.f;
            }
        }
        glProg.setVar("whitePointScale", whitePointScale);
        if (params != null && params.CCT != null) {
            float[] cct = params.CCT.matrix;
            if (params.CCT.correctionMode == ColorCorrectionTransform.CorrectionMode.MATRIXES) {
                cct = params.CCT.combineMatrix(params.whitePoint);
            }
            // White balance is already embedded in sensorToProPhoto (see
            // tofloat.glsl / initial.glsl: the division and multiplication by
            // whitePoint cancel), so neutralPoint is identity here.
            glProg.setVar("sensorToIntermediate", params.sensorToProPhoto);
            glProg.setVar("intermediateToSRGB", cct);
            glProg.setVar("neutralPoint", 1.f, 1.f, 1.f);
        } else {
            // No capture result yet: fall back to the plain white-balance
            // gains, the same values the native histogram is built with.
            glProg.setVar("sensorToIntermediate", IDENTITY);
            glProg.setVar("intermediateToSRGB", IDENTITY);
            glProg.setVar("neutralPoint", pipeline.gains[0], pipeline.gains[1], pipeline.gains[2]);
        }
        glProg.setTexture("GainMap", pipeline.ensureGainMap(params));
        glProg.setVar("gainMapTransform", pipeline.gainMapTransform(params));
        glProg.setTexture("ToneCurve", pipeline.currentToneCurve());
        glProg.setTextureCompute("InputBuffer", input, false);
        glProg.setTextureCompute("OutputBuffer", basePipeline.main1, true);
        glProg.computeAuto(new Point(input.mSize.x, input.mSize.y), 1);
        WorkingTexture = basePipeline.main1;
        OwnTexture = false; // written by imageStore; no fragment draw
    }
}
