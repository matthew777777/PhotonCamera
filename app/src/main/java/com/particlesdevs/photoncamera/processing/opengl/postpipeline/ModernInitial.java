package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.processing.render.ColorCorrectionTransform;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;
import com.particlesdevs.photoncamera.util.Log;

import java.util.Arrays;

public class ModernInitial extends Node {
    private static final String TAG = "ModernInitial";

    @Tunable(
        title = "Modern Exposure Compensation",
        category = "Modern Core",
        subTab = "Experimental Pipeline",
        min = -5.0f,
        max = 5.0f,
        defaultValue = 0.0f,
        description = "Exposure compensation in EV stops"
    )
    float exposureCompensation;

    @Tunable(
        title = "Manual Exposure Scale",
        category = "Modern Core",
        subTab = "Experimental Pipeline",
        min = 0.01f,
        max = 100.0f,
        defaultValue = 1.0f,
        description = "Manual exposure multiplier"
    )
    float manualExposureScale = 1.0f;

    @Tunable(
        title = "Contrast",
        category = "Modern Core",
        subTab = "Experimental Pipeline",
        min = 0.5f,
        max = 2.0f,
        defaultValue = 1.0f,
        description = "Contrast adjustment (pivot at 0.18 linear midtone)"
    )
    float contrast = 1.0f;

    @Tunable(
        title = "Saturation",
        category = "Modern Core",
        subTab = "Experimental Pipeline",
        min = 0.0f,
        max = 2.0f,
        defaultValue = 1.0f,
        description = "Color saturation in linear space"
    )
    float saturation = 1.0f;

    @Tunable(
        title = "Vignette Correction",
        category = "Modern Core",
        subTab = "Experimental Pipeline",
        min = 0.0f,
        max = 2.0f,
        defaultValue = 1.0f,
        description = "Multiplier for lens shading correction"
    )
    float vignetteCorrection = 1.0f;

    public ModernInitial() {
        super("", "ModernInitial");
    }

    @Override
    public void Compile() {
    }

    @Override
    public void Run() {
        float evScale = (float) Math.pow(2.0, exposureCompensation);
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

        // Safety check: fall back to identity if matrices are missing/malformed.
        if (sensorToIntermediate == null || sensorToIntermediate.length < 9
                || (sensorToIntermediate[0] == 0 && sensorToIntermediate[4] == 0)) {
            Log.w(TAG, "sensorToIntermediate is uninitialized, using identity");
            sensorToIntermediate = new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
        }
        if (intermediateToSRGB == null || intermediateToSRGB.length < 9
                || (intermediateToSRGB[0] == 0 && intermediateToSRGB[4] == 0)) {
            Log.w(TAG, "intermediateToSRGB is uninitialized, using identity");
            intermediateToSRGB = new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
        }

        Log.d(TAG, "Run() - totalExposureScale: " + totalExposureScale);
        Log.d(TAG, "Run() - sensorToIntermediate: " + Arrays.toString(sensorToIntermediate));
        Log.d(TAG, "Run() - intermediateToSRGB: " + Arrays.toString(intermediateToSRGB));

        // The pipeline already builds and owns one shared GainMap texture
        // (see PostPipeline.GainMap / Bayer2Float) - reuse it by reference
        // instead of re-uploading the raw gain data from scratch every frame.
        PostPipeline pipeline = (PostPipeline) basePipeline;
        boolean useGainMap = pipeline.GainMap != null;

        // 1. Set Defines and Program FIRST
        glProg.setDefine("USE_GAINMAP", useGainMap);
        ColorCorrectionTransform.CorrectionMode correctionMode = basePipeline.mParameters.CCT.correctionMode;
        boolean useCubeCorrection = correctionMode == ColorCorrectionTransform.CorrectionMode.CUBE
            || correctionMode == ColorCorrectionTransform.CorrectionMode.CUBES;
        glProg.setDefine("CCT_CUBE", useCubeCorrection);
        // Noise model (slope/offset) - used to fade the shading correction out
        // in noisy/dark regions, same role NOISES/NOISEO play in initial.glsl.
        glProg.setDefine("NOISES", basePipeline.noiseS);
        glProg.setDefine("NOISEO", basePipeline.noiseO);
        glProg.useAssetProgram("modern_initial");

        // 2. Set Uniforms AFTER program is active
        glProg.setVar("exposureScale", totalExposureScale);
        glProg.setVar("contrast", contrast);
        glProg.setVar("saturation", saturation);
        glProg.setVar("vignette", vignetteCorrection);
        glProg.setVar("sensorToIntermediate", sensorToIntermediate);
        glProg.setVar("intermediateToSRGB", intermediateToSRGB);

        if (useCubeCorrection) {
            float[][] cube;
            if (correctionMode == ColorCorrectionTransform.CorrectionMode.CUBES) {
                cube = basePipeline.mParameters.CCT.cubes[0].Combine(
                        basePipeline.mParameters.CCT.cubes[1], basePipeline.mParameters.whitePoint);
            } else {
                cube = basePipeline.mParameters.CCT.cubes[0].cube;
            }
            glProg.setVar("CUBE0", cube[0]);
            glProg.setVar("CUBE1", cube[1]);
            glProg.setVar("CUBE2", cube[2]);
        }

        float[] WP = basePipeline.mParameters.whitePoint;
        if (WP == null || WP.length < 3 || WP[0] == 0) WP = new float[]{1.0f, 1.0f, 1.0f};
        glProg.setVar("whitePoint", WP);

        glProg.setTexture("InputBuffer", previousNode.WorkingTexture);

        // Lens shading correction (GainMap) - bind the pipeline's shared texture.
        if (useGainMap) {
            glProg.setTexture("GainMap", pipeline.GainMap);
        }

        WorkingTexture = basePipeline.getMain();
        glProg.drawBlocks(WorkingTexture);
        glProg.closed = true;
    }
}
