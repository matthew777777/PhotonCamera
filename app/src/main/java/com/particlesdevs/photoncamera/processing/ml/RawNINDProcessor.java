package com.particlesdevs.photoncamera.processing.ml;

import android.content.Context;
import com.particlesdevs.photoncamera.util.Log;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.providers.NNAPIFlags;
import com.particlesdevs.photoncamera.api.NativeEngine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

public class RawNINDProcessor {
    private static final String TAG = "RawNINDProcessor";
    private static final String MODEL_NAME = "rawnind_utnet2.onnx";

    private OrtEnvironment env;
    private OrtSession session;
    private String inputName;
    private String outputName;

    public FloatBuffer lastAccumulatedOutput;
    public FloatBuffer lastWeightSum;

    public RawNINDProcessor(Context context) {
        try {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();

            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE);
            options.setSessionLogVerbosityLevel(0);

            try {
                // Try WEBGPU first
                Map<String, String> webgpuOptions = new HashMap<>();
                options.addWebGPU(webgpuOptions);
                Log.d(TAG, "RawNIND: WebGPU acceleration requested");
            } catch (OrtException e) {
                Log.d(TAG, "RawNIND: WebGPU not available, trying NNAPI");
                try {
                    options.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16));
                    Log.d(TAG, "RawNIND: NNAPI hardware acceleration requested");
                } catch (OrtException e2) {
                    Log.w(TAG, "RawNIND: No hardware acceleration available, using CPU");
                }
            }
            
            options.setInterOpNumThreads(4);
            options.setIntraOpNumThreads(4);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

            byte[] modelBytes = loadModel(context);
            if (modelBytes != null) {
                session = env.createSession(modelBytes, options);
                inputName = session.getInputNames().iterator().next();
                outputName = session.getOutputNames().iterator().next();

                Log.d(TAG, "RawNIND: ONNX Session created. Input: " + inputName + ", Output: " + outputName);
            }
        } catch (Throwable e) {
            Log.e(TAG, "RawNIND: Error initializing ONNX Runtime", e);
        }
    }

    private byte[] loadModel(Context context) {
        try (InputStream is = context.getAssets().open("models/" + MODEL_NAME)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int nRead;
            byte[] data = new byte[16384];
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            Log.w(TAG, "RawNIND: Model file not found in assets: " + MODEL_NAME);
            return null;
        }
    }

    public float[] runInference(float[] inputData, int width, int height) {
        if (session == null) return null;

        try {
            long startTimeTotal = System.currentTimeMillis();
            final int TILE_SIZE = 512;
            final int TILE_SIZE_OUT = 1024;
            final int OVERLAP = 32;
            final int STRIDE = TILE_SIZE - OVERLAP;

            // USE DIRECT BUFFERS to avoid JNI copy overhead and allow additive weight blending
            FloatBuffer flattenedOutput = ByteBuffer.allocateDirect(width * height * 4 * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();

            FloatBuffer weightSum = ByteBuffer.allocateDirect(width * height * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();

            int tilesY = (height > TILE_SIZE) ? (int) Math.ceil((float)(height - OVERLAP) / STRIDE) : 1;
            int tilesX = (width > TILE_SIZE) ? (int) Math.ceil((float)(width - OVERLAP) / STRIDE) : 1;

            Log.d(TAG, "RawNIND: Processing " + (tilesX * tilesY) + " seamless tiles via WeightSum");

            float[] tileInput = new float[TILE_SIZE * TILE_SIZE * 4];
            float[] tileOutputBuf = new float[TILE_SIZE_OUT * TILE_SIZE_OUT * 3];

            float avgIn = 0, avgOut = 0;
            boolean gainMeasured = false;

            for (int ty = 0; ty < tilesY; ty++) {
                for (int tx = 0; tx < tilesX; tx++) {
                    int startY = Math.min(ty * STRIDE, height - TILE_SIZE);
                    int startX = Math.min(tx * STRIDE, width - TILE_SIZE);
                    startY = Math.max(0, startY);
                    startX = Math.max(0, startX);

                    for (int c = 0; c < 4; c++) {
                        for (int y = 0; y < TILE_SIZE; y++) {
                            System.arraycopy(
                                    inputData, c * width * height + (startY + y) * width + startX,
                                    tileInput, c * TILE_SIZE * TILE_SIZE + y * TILE_SIZE,
                                    TILE_SIZE
                            );
                        }
                    }

                    if (!gainMeasured) {
                        float sum = 0;
                        for (float v : tileInput) sum += Math.max(0, v);
                        avgIn = sum / (TILE_SIZE * TILE_SIZE * 4);
                    }

                    long[] shape = {1, 4, TILE_SIZE, TILE_SIZE};
                    try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(tileInput), shape);
                         OrtSession.Result result = session.run(Collections.singletonMap(inputName, inputTensor))) {

                        OnnxTensor outputTensor = (OnnxTensor) result.get(outputName).orElse(null);
                        if (outputTensor == null) continue;

                        outputTensor.getFloatBuffer().get(tileOutputBuf);

                        if (!gainMeasured) {
                            float sum = 0;
                            for (float v : tileOutputBuf) sum += Math.max(0, v);
                            avgOut = (sum / (TILE_SIZE_OUT * TILE_SIZE_OUT * 3)) / 65535.0f;
                            gainMeasured = true;
                        }

                        NativeEngine.nativeShuffleTileRes(
                                tileOutputBuf,
                                flattenedOutput,
                                weightSum,
                                startX,
                                startY,
                                TILE_SIZE,
                                TILE_SIZE,
                                width,
                                height,
                                TILE_SIZE_OUT,
                                3
                        );
                    }
                }
            }

            Log.d(TAG, "RawNIND: Total inference time: " + (System.currentTimeMillis() - startTimeTotal) + "ms");

            float brightnessCorrection = (avgOut > 0.0001f) ? (avgIn / avgOut) : 1.0f;

            // Return result with weights for final normalization in Node
            float[] finalArray = new float[width * height * 4 + 1];
            // Weight normalization will happen in JNI's applyRawNINDOutput
            // But we need to pass the weights to the Node's output array?
            // Actually, we pass the DirectBuffers to JNI in the next step.
            // Let's pass the Buffers as a special return type or store them in Processor.

            // For now, let's keep the return as float[] for compatibility but wrap the buffers
            this.lastAccumulatedOutput = flattenedOutput;
            this.lastWeightSum = weightSum;

            finalArray[finalArray.length - 1] = brightnessCorrection;
            return finalArray;
        } catch (Throwable e) {
            Log.e(TAG, "RawNIND: Error during inference", e);
            return null;
        }
    }

    public void close() {
        try {
            if (session != null) session.close();
            if (env != null) env.close();
        } catch (OrtException e) {
            Log.e(TAG, "RawNIND: Error closing ONNX Runtime", e);
        }
    }
}
