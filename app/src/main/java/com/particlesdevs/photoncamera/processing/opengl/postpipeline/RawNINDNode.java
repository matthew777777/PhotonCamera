package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;
import com.particlesdevs.photoncamera.util.Log;
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
        stackBuffer.rewind();
        ShortBuffer shortBuffer = stackBuffer.asShortBuffer();

        float[] inputData = new float[inputH * inputW * 4];
        float whiteLevel = (float) basePipeline.mParameters.whiteLevel;
        float[] blackLevels = basePipeline.mParameters.blackLevel;

        // Map physical Bayer cell (2x2) to AI channels directly
        // Channel 0: (0,0), Channel 1: (0,1), Channel 2: (1,0), Channel 3: (1,1)
        // This matches the order of Android's blackLevel pattern
        int planeSize = inputH * inputW;
        for (int y = 0; y < inputH; y++) {
            for (int x = 0; x < inputW; x++) {
                int baseIdx = (y * 2 * rawSize.x + x * 2);
                int flatIdx = y * inputW + x;

                inputData[flatIdx] = ((shortBuffer.get(baseIdx) & 0xFFFF) - blackLevels[0]) / whiteLevel;
                inputData[planeSize + flatIdx] = ((shortBuffer.get(baseIdx + 1) & 0xFFFF) - blackLevels[1]) / whiteLevel;
                inputData[2 * planeSize + flatIdx] = ((shortBuffer.get(baseIdx + rawSize.x) & 0xFFFF) - blackLevels[2]) / whiteLevel;
                inputData[3 * planeSize + flatIdx] = ((shortBuffer.get(baseIdx + rawSize.x + 1) & 0xFFFF) - blackLevels[3]) / whiteLevel;
            }
        }

        // 2. Run Inference
        float[] outputData = processor.runInference(inputData, inputW, inputH);

        if (outputData != null) {
            // 3. Blend and Re-mosaic back into shortBuffer
            for (int y = 0; y < inputH; y++) {
                for (int x = 0; x < inputW; x++) {
                    int baseIdx = (y * 2 * rawSize.x + x * 2);
                    int flatIdx = y * inputW + x;

                    float d0 = outputData[flatIdx] * whiteLevel + blackLevels[0];
                    float d1 = outputData[planeSize + flatIdx] * whiteLevel + blackLevels[1];
                    float d2 = outputData[2 * planeSize + flatIdx] * whiteLevel + blackLevels[2];
                    float d3 = outputData[3 * planeSize + flatIdx] * whiteLevel + blackLevels[3];

                    short p0 = (short) Math.max(0, Math.min(65535, (1.0f - strength) * (shortBuffer.get(baseIdx) & 0xFFFF) + strength * d0));
                    short p1 = (short) Math.max(0, Math.min(65535, (1.0f - strength) * (shortBuffer.get(baseIdx + 1) & 0xFFFF) + strength * d1));
                    short p2 = (short) Math.max(0, Math.min(65535, (1.0f - strength) * (shortBuffer.get(baseIdx + rawSize.x) & 0xFFFF) + strength * d2));
                    short p3 = (short) Math.max(0, Math.min(65535, (1.0f - strength) * (shortBuffer.get(baseIdx + rawSize.x + 1) & 0xFFFF) + strength * d3));

                    shortBuffer.put(baseIdx, p0);
                    shortBuffer.put(baseIdx + 1, p1);
                    shortBuffer.put(baseIdx + rawSize.x, p2);
                    shortBuffer.put(baseIdx + rawSize.x + 1, p3);
                }
            }
            Log.d(TAG, "RawNIND re-mosaicing completed");
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
