package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.processing.render.ColorCorrectionTransform;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;
import com.particlesdevs.photoncamera.util.Log;

import java.util.Arrays;

public class ModernInitial extends Node {
    private static final String TAG = "ModernInitial";

    @Tunable(
        title = "Modern Exposure Compensation",
        category = "Modern Core",
        min = -5.0f,
        max = 5.0f,
        defaultValue = 0.0f,
        description = "Exposure compensation in EV stops"
    )
    float exposureCompensation;

    @Tunable(
        title = "Manual Exposure Scale",
        category = "Modern Core",
        min = 0.01f,
        max = 100.0f,
        defaultValue = 1.0f,
        description = "Manual exposure multiplier"
    )
    float manualExposureScale = 1.0f;

    public ModernInitial() {
        super("", "ModernInitial");
    }

    @Override
    public void Compile() {
    }

    @Override
    public void AfterRun() {
        if (GainMapTex != null) GainMapTex.close();
    }

    private GLTexture GainMapTex;

    @Override
    public void Run() {
        // Use modern exposure compensation from settings if not overridden by tunable
        float compensation = PreferenceKeys.getModernExposureCompensation();
        float evScale = (float) Math.pow(2.0, compensation + exposureCompensation);
        float totalExposureScale = evScale * manualExposureScale;

        // Get color matrices from parameters
        float[] sensorToIntermediate = basePipeline.mParameters.sensorToProPhoto;
        float[] intermediateToSRGB;

        ColorCorrectionTransform.CorrectionMode mode = basePipeline.mParameters.CCT.correctionMode;
        if (mode == ColorCorrectionTransform.CorrectionMode.MATRIXES) {
            intermediateToSRGB = basePipeline.mParameters.CCT.combineMatrix(basePipeline.mParameters.whitePoint);
        } else {
            intermediateToSRGB = basePipeline.mParameters.CCT.matrix;
        }

        // Safety check: Ensure matrices are not all zeros
        if (sensorToIntermediate == null || sensorToIntermediate[0] == 0 && sensorToIntermediate[4] == 0) {
            Log.w(TAG, "sensorToIntermediate is uninitialized, using identity");
            sensorToIntermediate = new float[]{1,0,0, 0,1,0, 0,0,1};
        }
        if (intermediateToSRGB == null || intermediateToSRGB[0] == 0 && intermediateToSRGB[4] == 0) {
            Log.w(TAG, "intermediateToSRGB is uninitialized, using identity");
            intermediateToSRGB = new float[]{1,0,0, 0,1,0, 0,0,1};
        }

        Log.d(TAG, "Run() - totalExposureScale: " + totalExposureScale);
        Log.d(TAG, "Run() - sensorToIntermediate: " + Arrays.toString(sensorToIntermediate));
        Log.d(TAG, "Run() - intermediateToSRGB: " + Arrays.toString(intermediateToSRGB));

        // 1. Set Defines and Program FIRST
        if (basePipeline.mParameters.gainMap != null) {
            glProg.setDefine("USE_GAINMAP", 1);
        } else {
            glProg.setDefine("USE_GAINMAP", 0);
        }
        glProg.useAssetProgram("modern_initial");

        // 2. Set Uniforms AFTER program is active
        glProg.setVar("exposureScale", totalExposureScale);
        glProg.setVar("sensorToIntermediate", sensorToIntermediate);
        glProg.setVar("intermediateToSRGB", intermediateToSRGB);

        float[] WP = basePipeline.mParameters.whitePoint;
        if (WP == null || WP[0] == 0) WP = new float[]{1.0f, 1.0f, 1.0f};
        glProg.setVar("whitePoint", WP);
        glProg.setVar("noiseS", basePipeline.noiseS);
        glProg.setVar("noiseO", basePipeline.noiseO);

        glProg.setTexture("InputBuffer", previousNode.WorkingTexture);

        // Lens shading correction (GainMap)
        if (basePipeline.mParameters.gainMap != null) {
            GainMapTex = new GLTexture(basePipeline.mParameters.mapSize, new GLFormat(GLFormat.DataType.FLOAT_16, 4),
                    com.particlesdevs.photoncamera.util.BufferUtils.getFrom(basePipeline.mParameters.gainMap),
                    android.opengl.GLES20.GL_LINEAR, android.opengl.GLES20.GL_CLAMP_TO_EDGE);
            glProg.setTexture("GainMap", GainMapTex);
        }

        WorkingTexture = basePipeline.getMain();
        glProg.drawBlocks(WorkingTexture);
        glProg.closed = true;
    }
}
