package com.particlesdevs.photoncamera.processing.ml;

import android.content.Context;
import com.particlesdevs.photoncamera.util.Log;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;

public class RawNINDProcessor {
    private static final String TAG = "RawNINDProcessor";
    private static final String MODEL_NAME = "rawnind_utnet2.onnx";
    
    private OrtEnvironment env;
    private OrtSession session;

    public RawNINDProcessor(Context context) {
        try {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            try {
                options.addNnapi(); // Enable NNAPI for hardware acceleration
                Log.d(TAG, "NNAPI acceleration enabled");
            } catch (OrtException e) {
                Log.w(TAG, "NNAPI not supported on this device, falling back to CPU", e);
            }
            
            byte[] modelBytes = loadModel(context);
            if (modelBytes != null) {
                session = env.createSession(modelBytes, options);
                Log.d(TAG, "ONNX Session created successfully");
            }
        } catch (OrtException | IOException e) {
            Log.e(TAG, "Error initializing ONNX Runtime", e);
        }
    }

    private byte[] loadModel(Context context) throws IOException {
        try (InputStream is = context.getAssets().open("models/" + MODEL_NAME)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int nRead;
            byte[] data = new byte[16384];
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            Log.w(TAG, "Model file not found in assets: " + MODEL_NAME);
            return null;
        }
    }

    public float[] runInference(float[] inputData, int width, int height) {
        if (session == null) return null;

        try {
            // UtNet2 requires H, W to be multiples of 16
            int paddedW = (width + 15) / 16 * 16;
            int paddedH = (height + 15) / 16 * 16;
            
            float[] paddedInput;
            if (paddedW == width && paddedH == height) {
                paddedInput = inputData;
            } else {
                paddedInput = new float[paddedW * paddedH * 4];
                for (int c = 0; c < 4; c++) {
                    for (int y = 0; y < height; y++) {
                        System.arraycopy(inputData, c * width * height + y * width, 
                                       paddedInput, c * paddedW * paddedH + y * paddedW, width);
                    }
                }
            }

            long[] shape = {1, 4, paddedH, paddedW};
            FloatBuffer inputBuffer = FloatBuffer.wrap(paddedInput);
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputBuffer, shape);
            
            OrtSession.Result result = session.run(Collections.singletonMap("input", inputTensor));
            OnnxTensor outputTensor = (OnnxTensor) result.get(0);
            long[] outShape = outputTensor.getInfo().getShape();
            int outChannels = (int) outShape[1];
            
            float[] flattened = new float[width * height * 4];
            float[][][][] outputData = (float[][][][]) outputTensor.getValue();

            // RawNIND Bayer model should output 4 channels (R, G1, G2, B)
            // If it outputs 3, we treat it as RGB and fill 4th as 1.0, but here we expect 4
            int processChannels = Math.min(outChannels, 4);
            for (int c = 0; c < processChannels; c++) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        flattened[c * width * height + y * width + x] = outputData[0][c][y][x];
                    }
                }
            }
            
            return flattened;
        } catch (OrtException e) {
            Log.e(TAG, "Error during inference", e);
            return null;
        }
    }

    public void close() {
        try {
            if (session != null) session.close();
            if (env != null) env.close();
        } catch (OrtException e) {
            Log.e(TAG, "Error closing ONNX Runtime", e);
        }
    }
}
