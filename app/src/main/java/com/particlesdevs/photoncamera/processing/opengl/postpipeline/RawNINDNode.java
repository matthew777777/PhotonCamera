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
        float[] outputData1 = processor.runInference(inputData, inputW, inputH);
        
        if (outputData1 != null) {
            float[] finalOutput;
            float blendWeight;
            
            if (strength > 1.0f) {
                // Pass 2: Run inference on the result of Pass 1
                finalOutput = processor.runInference(outputData1, inputW, inputH);
                if (finalOutput == null) {
                    finalOutput = outputData1;
                    blendWeight = 1.0f;
                } else {
                    blendWeight = strength - 1.0f;
                }
            } else {
                finalOutput = outputData1;
                blendWeight = strength;
            }

            // 3. Blend and Re-mosaic back into shortBuffer
            for (int y = 0; y < inputH; y++) {
                for (int x = 0; x < inputW; x++) {
                    int baseIdx = (y * 2 * rawSize.x + x * 2);
                    int flatIdx = y * inputW + x;
                    
                    float d0, d1, d2, d3;
                    float o0, o1, o2, o3;

                    if (strength > 1.0f) {
                        // Blend between 1x and 2x
                        d0 = finalOutput[flatIdx] * whiteLevel + blackLevels[0];
                        d1 = finalOutput[planeSize + flatIdx] * whiteLevel + blackLevels[1];
                        d2 = finalOutput[2 * planeSize + flatIdx] * whiteLevel + blackLevels[2];
                        d3 = finalOutput[3 * planeSize + flatIdx] * whiteLevel + blackLevels[3];
                        
                        o0 = outputData1[flatIdx] * whiteLevel + blackLevels[0];
                        o1 = outputData1[planeSize + flatIdx] * whiteLevel + blackLevels[1];
                        o2 = outputData1[2 * planeSize + flatIdx] * whiteLevel + blackLevels[2];
                        o3 = outputData1[3 * planeSize + flatIdx] * whiteLevel + blackLevels[3];
                    } else {
                        // Blend between Original and 1x
                        d0 = outputData1[flatIdx] * whiteLevel + blackLevels[0];
                        d1 = outputData1[planeSize + flatIdx] * whiteLevel + blackLevels[1];
                        d2 = outputData1[2 * planeSize + flatIdx] * whiteLevel + blackLevels[2];
                        d3 = outputData1[3 * planeSize + flatIdx] * whiteLevel + blackLevels[3];
                        
                        o0 = (shortBuffer.get(baseIdx) & 0xFFFF);
                        o1 = (shortBuffer.get(baseIdx + 1) & 0xFFFF);
                        o2 = (shortBuffer.get(baseIdx + rawSize.x) & 0xFFFF);
                        o3 = (shortBuffer.get(baseIdx + rawSize.x + 1) & 0xFFFF);
                    }
                    
                    short p0 = (short) Math.max(0, Math.min(65535, (1.0f - blendWeight) * o0 + blendWeight * d0));
                    short p1 = (short) Math.max(0, Math.min(65535, (1.0f - blendWeight) * o1 + blendWeight * d1));
                    short p2 = (short) Math.max(0, Math.min(65535, (1.0f - blendWeight) * o2 + blendWeight * d2));
                    short p3 = (short) Math.max(0, Math.min(65535, (1.0f - blendWeight) * o3 + blendWeight * d3));
                    
                    shortBuffer.put(baseIdx, p0);
                    shortBuffer.put(baseIdx + 1, p1);
                    shortBuffer.put(baseIdx + rawSize.x, p2);
                    shortBuffer.put(baseIdx + rawSize.x + 1, p3);
                }
            }
            Log.d(TAG, "RawNIND re-mosaicing completed (" + (strength > 1.0f ? "2nd pass" : "1st pass") + ")");
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
