package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;
import com.particlesdevs.photoncamera.util.Log;
import com.particlesdevs.photoncamera.api.NativeEngine;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.ml.RawNINDProcessor;
import com.particlesdevs.photoncamera.processing.opengl.GLDrawParams;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;

public class RawNINDNode extends Node {
    private static final String TAG = "RawNINDNode";
    private RawNINDProcessor processor;

    public RawNINDNode() {
        super("", "RawNIND");
    }

    @Override
    public void Compile() {
    }

    @Override
    public void Run() {
        PostPipeline postPipeline = (PostPipeline) basePipeline;
        Point rawSize = basePipeline.mParameters.rawSize;
        float strength = PreferenceKeys.getRawNindStrength();

        if (strength <= 0.0f) {
            return;
        }

        // 1. Inference setup
        int inputH = rawSize.y / 2;
        int inputW = rawSize.x / 2;

        if (processor == null) {
            processor = new RawNINDProcessor(PhotonCamera.getContext());
        }

        ByteBuffer stackBuffer = postPipeline.stackFrame;
        float[] inputData = new float[inputH * inputW * 4];
        float whiteLevel = (float) basePipeline.mParameters.whiteLevel;
        float[] blackLevels = basePipeline.mParameters.blackLevel;

        NativeEngine.nativePrepareRawNINDInput(
                stackBuffer,
                inputData,
                inputW,
                inputH,
                rawSize.x,
                whiteLevel,
                blackLevels
        );

        // 2. Run Inference
        float[] outputDataWithMeta = processor.runInference(inputData, inputW, inputH);

        if (outputDataWithMeta != null) {
            float brightnessCorrection = outputDataWithMeta[outputDataWithMeta.length - 1];

            // 3. Blend and Re-mosaic back into stackBuffer via JNI using WeightSum normalization
            NativeEngine.nativeApplyRawNINDOutput(
                    stackBuffer,
                    processor.lastAccumulatedOutput,
                    processor.lastWeightSum,
                    inputW,
                    inputH,
                    rawSize.x,
                    whiteLevel,
                    blackLevels,
                    strength,
                    brightnessCorrection
            );
            Log.d(TAG, "RawNIND seamless re-mosaicing completed via JNI with correction: " + brightnessCorrection);
        } else {
            Log.d(TAG, "RawNIND re-mosaicing FAILED via JNI");
        }
    }

    @Override
    public void AfterRun() {
        if (processor != null) {
            processor.close();
            processor = null;
        }
    }
}
