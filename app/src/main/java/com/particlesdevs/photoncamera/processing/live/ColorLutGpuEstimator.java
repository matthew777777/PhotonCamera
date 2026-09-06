package com.particlesdevs.photoncamera.processing.live;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLES31;

import com.particlesdevs.photoncamera.processing.opengl.GLBuffer;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;

/**
 * Fits the viewfinder's 17^3 color LUT on the GPU with two compute
 * dispatches, replacing the CPU trilinear-splat estimator and its per-fit
 * data traffic (two 300 KB image readbacks). lut_fit_accumulate splats every
 * frame pixel into the LUT grid with fixed-point atomicAdd; lut_fit_finalize
 * applies the identity prior and writes the LUT. The preferred write path is
 * an image3D store straight into the sampled texture (zero copies, fully
 * async); drivers that reject the image bind fall back to a PBO upload, and
 * if that is rejected too, to a mapped client-pointer upload. The fit inputs
 * are always sampled textures: both come out of framebuffer renders and stay
 * attached to their FBOs, which several ES drivers reject for
 * glBindImageTexture (GL_INVALID_OPERATION on Adreno).
 */
public final class ColorLutGpuEstimator {
    private static final String TAG = "ColorLutGpuEstimator";
    /** Must match kSize in lut_fit_*.glsl and the preview shader's 17^3 sampling. */
    public static final int LUT_SIZE = 17;
    private static final int LUT_CELLS = LUT_SIZE * LUT_SIZE * LUT_SIZE;
    private static final int ACCUMULATE_GROUP = 8;  // lut_fit_accumulate local_size_x/y
    private static final int FINALIZE_GROUP = 64;   // lut_fit_finalize local_size_x
    /** Texture units: raw ISP input and the sampled LUT; TargetBuffer gets
     *  unit 0 from GLProg.setTexture. */
    private static final int INPUT_TEXTURE_UNIT = 1;
    private static final int LUT_TEXTURE_UNIT = 1;
    private static final int LUT_IMAGE_UNIT = 0;
    /** SSBO binding points, for leaving no bindings behind after a fit; the
     *  shaders' layout() qualifiers are the source of truth. */
    private static final int SUMS_BINDING_ACCUMULATE = 2;
    private static final int SUMS_BINDING_FINALIZE = 1;
    private static final int OUT_BINDING = 2;

    private static final int WRITE_UNDECIDED = 0;
    /** imageStore into the 3D texture: zero copies, fully async. */
    private static final int WRITE_IMAGE = 1;
    /** glTexSubImage3D sourced from the finalize SSBO as pixel-unpack buffer. */
    private static final int WRITE_PBO = 2;
    /** Mapped client-pointer upload - always works, costs a sync per fit. */
    private static final int WRITE_MAP = 3;

    private GLProg program;
    private GLBuffer lutSums;
    private GLBuffer lutOut;
    private int lutTexture;
    private boolean lutValid;
    private int lutWriteMode = WRITE_UNDECIDED;
    private int strideParity;
    private int fitCounter;
    private int cornerLogsRemaining = 6;

    /** The GL context was recreated: every name is invalid, so just forget them. */
    public void reset() {
        program = null;
        lutSums = null;
        lutOut = null;
        lutTexture = 0;
        lutValid = false;
        lutWriteMode = WRITE_UNDECIDED;
        strideParity = 0;
        fitCounter = 0;
        cornerLogsRemaining = 6;
    }

    /** Drops the current model; the preview falls back to the plain ISP image. */
    public void invalidate() {
        lutValid = false;
    }

    public boolean hasLut() {
        return lutValid && lutTexture != 0;
    }

    /** GL name of the RGBA16F 17^3 texture; only valid once {@link #hasLut()}. */
    public int getLutTexture() {
        return lutTexture;
    }

    /**
     * Fits the LUT that maps {@code inputTexture} (ISP preview, RGBA8, raw GL
     * texture name) onto {@code target} (processed RAW, RGBA8 GLTexture).
     * Both are {@code width x height} and already share the RAW frame's
     * sensor-oriented row order. Must run on the GL thread. Leaves its
     * compute program current - the caller must restore the render program
     * before drawing.
     */
    public void fit(int inputTexture, GLTexture target, int width, int height) {
        ensureResources();
        // Start from a clean error flag so each check below attributes errors
        // to exactly one call (the camera's per-frame EGLImage recreation
        // leaves sticky vendor errors around otherwise).
        clearGlErrors();
        // Order the ISP downsample draw and the streamed node writes before
        // the compute texture fetches; ALL covers the framebuffer, image and
        // SSBO sides in one call.
        GLES31.glMemoryBarrier(GLES31.GL_ALL_BARRIER_BITS);
        program.useAssetProgram("preview/lut_fit_accumulate", true);
        program.setTexture("TargetBuffer", target);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + INPUT_TEXTURE_UNIT);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture);
        GLES20.glUniform1i(GLES31.glGetUniformLocation(
                program.mCurrentProgramActive, "InputBuffer"), INPUT_TEXTURE_UNIT);
        program.setBufferCompute("LutSums", lutSums);
        // Stride-2 splat with per-fit parity flip: half the atomics each fit,
        // full-frame coverage across consecutive fits.
        strideParity ^= 1;
        program.setVar("uParity", strideParity);
        int gridWidth = (width + 1) / 2;
        int gridHeight = (height + 1) / 2;
        GLES31.glDispatchCompute((gridWidth + ACCUMULATE_GROUP - 1) / ACCUMULATE_GROUP,
                (gridHeight + ACCUMULATE_GROUP - 1) / ACCUMULATE_GROUP, 1);
        check("accumulate dispatch");
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
        // Finalize/write every second fit only: each LUT then carries two
        // accumulated frames - both stride parities, i.e. full-frame coverage
        // and double sample density - while the finalize dispatch, 3D texture
        // write and barriers run at half rate.
        if (++fitCounter % 2 != 0) return;

        if (lutWriteMode == WRITE_UNDECIDED) decideLutWriteMode();
        boolean imageMode = lutWriteMode == WRITE_IMAGE;
        program.setDefine("LUT_IMAGE_STORE", imageMode);
        program.useAssetProgram("preview/lut_fit_finalize", true);
        program.setBufferCompute("LutSums", lutSums);
        if (imageMode) {
            GLES31.glBindImageTexture(LUT_IMAGE_UNIT, lutTexture, 0, true, 0,
                    GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F);
        } else {
            program.setBufferCompute("LutOut", ensureLutOut());
        }
        GLES31.glDispatchCompute((LUT_CELLS + FINALIZE_GROUP - 1) / FINALIZE_GROUP, 1, 1);
        check("finalize dispatch");
        if (imageMode) {
            GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT
                    | GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                    | GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
            // Leave no image binding behind: a texture simultaneously bound
            // as image and sampled by the preview draw would be a feedback loop.
            GLES31.glBindImageTexture(LUT_IMAGE_UNIT, 0, 0, true, 0,
                    GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F);
            lutValid = true;
            return;
        }

        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT
                | GLES31.GL_BUFFER_UPDATE_BARRIER_BIT);
        if (cornerLogsRemaining > 0) logLutCorners();
        if (lutWriteMode == WRITE_PBO) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + LUT_TEXTURE_UNIT);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture);
            GLES31.glBindBuffer(GLES31.GL_PIXEL_UNPACK_BUFFER, lutOut.mBufferID);
            GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_3D, 0, 0, 0, 0, LUT_SIZE, LUT_SIZE,
                    LUT_SIZE, GLES30.GL_RGBA, GLES30.GL_FLOAT, 0);
            if (!check("lut upload (pbo)")) {
                Log.w(TAG, "PBO LUT upload rejected; falling back to mapped upload");
                lutWriteMode = WRITE_MAP;
            }
            GLES31.glBindBuffer(GLES31.GL_PIXEL_UNPACK_BUFFER, 0);
        }
        if (lutWriteMode == WRITE_MAP) {
            // Plain client upload from the mapping - the path that always
            // worked. Costs a GPU drain per fit, hence only the fallback.
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, lutOut.mBufferID);
            ByteBuffer mapped = (ByteBuffer) GLES31.glMapBufferRange(
                    GLES31.GL_SHADER_STORAGE_BUFFER, 0, LUT_CELLS * 4 * Float.BYTES,
                    GLES31.GL_MAP_READ_BIT);
            if (mapped == null) {
                Log.w(TAG, "lutOut mapping failed: glError 0x"
                        + Integer.toHexString(GLES31.glGetError()));
                GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
                return; // keep the previous LUT
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + LUT_TEXTURE_UNIT);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture);
            GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_3D, 0, 0, 0, 0, LUT_SIZE, LUT_SIZE,
                    LUT_SIZE, GLES30.GL_RGBA, GLES30.GL_FLOAT,
                    mapped.order(ByteOrder.nativeOrder()).asFloatBuffer());
            check("lut upload (map)");
            GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER);
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
        }
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, SUMS_BINDING_ACCUMULATE, 0);
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, SUMS_BINDING_FINALIZE, 0);
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, OUT_BINDING, 0);
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_UPDATE_BARRIER_BIT);
        lutValid = true;
    }

    /** Probes once whether this driver accepts an image bind on the LUT
     *  texture (it is never FBO-attached, so even Adreno should). */
    private void decideLutWriteMode() {
        GLES31.glBindImageTexture(LUT_IMAGE_UNIT, lutTexture, 0, true, 0,
                GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F);
        boolean imageOk = GLES31.glGetError() == GLES31.GL_NO_ERROR;
        GLES31.glBindImageTexture(LUT_IMAGE_UNIT, 0, 0, true, 0,
                GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F);
        lutWriteMode = imageOk ? WRITE_IMAGE : WRITE_PBO;
        Log.d(TAG, "LUT write mode: " + (imageOk
                ? "image3D store (zero copy)"
                : "PBO upload (image3D bind rejected)"));
    }

    /** Early-fit diagnostic from the finalize SSBO: alternating corner values
     *  across updates mean the two fit inputs themselves alternate; stable
     *  corners with a wrong preview point at the write path. */
    private void logLutCorners() {
        cornerLogsRemaining--;
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, lutOut.mBufferID);
        ByteBuffer mapped = (ByteBuffer) GLES31.glMapBufferRange(
                GLES31.GL_SHADER_STORAGE_BUFFER, 0, LUT_CELLS * 4 * Float.BYTES,
                GLES31.GL_MAP_READ_BIT);
        if (mapped != null) {
            FloatBuffer values = mapped.order(ByteOrder.nativeOrder()).asFloatBuffer();
            StringBuilder text = new StringBuilder("fit LUT corners:");
            int[] samples = {0, LUT_CELLS / 2, LUT_CELLS - 1};
            for (int i : samples) {
                text.append(String.format(Locale.US, " [%.3f %.3f %.3f]",
                        values.get(i * 4), values.get(i * 4 + 1), values.get(i * 4 + 2)));
            }
            float peak = 0;
            for (int i = 0; i < LUT_CELLS * 4; i++) {
                float value = Math.abs(values.get(i));
                if (value > peak) peak = value;
            }
            text.append(String.format(Locale.US, " peak %.3f", peak));
            Log.d(TAG, text.toString());
        }
        GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER);
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
    }

    private GLBuffer ensureLutOut() {
        if (lutOut == null) {
            // One vec4 per LUT vertex; GLBuffer sizes elements per channel,
            // so a vec4 is four FLOAT_32 elements, 16 bytes each - the
            // tightly packed RGBA stride the 3D texture upload expects.
            lutOut = new GLBuffer(LUT_CELLS * 4, new GLFormat(GLFormat.DataType.FLOAT_32));
        }
        return lutOut;
    }

    private static void clearGlErrors() {
        while (GLES31.glGetError() != GLES31.GL_NO_ERROR) { /* discard */ }
    }

    /** @return true when no error was found. */
    private static boolean check(String op) {
        int error = GLES31.glGetError();
        if (error != GLES31.GL_NO_ERROR) {
            Log.w(TAG, op + ": glError 0x" + Integer.toHexString(error));
            return false;
        }
        return true;
    }

    private void ensureResources() {
        if (program == null) program = new GLProg();
        if (lutSums == null) {
            // One ivec4 per LUT vertex; GLBuffer sizes elements per channel,
            // so an ivec4 is four SIGNED_32 elements. Created zeroed; the
            // finalize pass re-zeroes it after every fit.
            lutSums = new GLBuffer(LUT_CELLS * 4, new GLFormat(GLFormat.DataType.SIGNED_32));
        }
        if (lutTexture == 0) {
            int[] name = new int[1];
            GLES30.glGenTextures(1, name, 0);
            lutTexture = name[0];
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture);
            GLES30.glTexStorage3D(GLES30.GL_TEXTURE_3D, 1, GLES30.GL_RGBA16F,
                    LUT_SIZE, LUT_SIZE, LUT_SIZE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE);
        }
        // GLBuffer's constructor binds to an out-of-range SSBO index, leaving
        // a stale error flag that would trip later checkEglError() calls.
        GLES31.glGetError();
    }
}
