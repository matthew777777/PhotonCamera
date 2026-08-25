package com.particlesdevs.photoncamera.processing.opengl;

import android.opengl.GLES20;
import android.opengl.GLES30;

import com.particlesdevs.photoncamera.api.Settings;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;

/**
 * Persistent OpenGL post pipeline for the low-resolution SuperPixel stream.
 * It uses the viewfinder's existing GL context and retains its textures/FBO
 * across frames, unlike the one-shot capture {@link PostPipeline}.
 */
public final class StreamedPostPipeline {
    private static final String TAG = "StreamedPostPipeline";
    private int program;
    private int inputTexture;
    private int outputTexture;
    private int framebuffer;
    private int inputUniform;
    private int saturationUniform;
    private int contrastUniform;
    private int shadowsUniform;
    private int compressorUniform;
    private int width;
    private int height;

    public void reset() {
        // Called after a new context is current. Names from the previous context
        // are already invalid, so simply forget them.
        program = inputTexture = outputTexture = framebuffer = 0;
        inputUniform = saturationUniform = contrastUniform = shadowsUniform = compressorUniform = -1;
        width = height = 0;
    }

    /** Processes tightly packed SuperPixel RGBA8 and writes back into that same buffer. */
    public ByteBuffer process(ByteBuffer pixels, int frameWidth, int frameHeight,
                              int restoreWidth, int restoreHeight) {
        if (pixels == null || !pixels.isDirect()
                || pixels.capacity() < frameWidth * frameHeight * 4) {
            throw new IllegalArgumentException("Streamed pipeline requires direct RGBA8 input");
        }
        ensureResources(frameWidth, frameHeight);
        Settings settings = PhotonCamera.getSettings();

        pixels.position(0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, frameWidth, frameHeight,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glViewport(0, 0, frameWidth, frameHeight);
        GLES20.glUseProgram(program);
        GLES20.glUniform1i(inputUniform, 0);
        GLES20.glUniform1f(saturationUniform, (float) settings.saturation);
        GLES20.glUniform1f(contrastUniform, (float) settings.contrastMpy);
        GLES20.glUniform1f(shadowsUniform, (float) settings.shadows);
        GLES20.glUniform1f(compressorUniform, (float) settings.compressor);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);

        pixels.position(0);
        GLES20.glReadPixels(0, 0, frameWidth, frameHeight, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, pixels);
        pixels.position(0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, restoreWidth, restoreHeight);
        return pixels;
    }

    private void ensureResources(int frameWidth, int frameHeight) {
        if (program == 0) {
            String vertex = "const vec2 p[3]=vec2[3](vec2(-1,-1),vec2(3,-1),vec2(-1,3));"
                    + "out vec2 texCoord;void main(){vec2 v=p[gl_VertexID];"
                    + "texCoord=v*0.5+0.5;gl_Position=vec4(v,0,1);}";
            String fragment = PhotonCamera.getAssetLoader().getString(
                    "shaders/preview/streamed_post_pipeline.glsl");
            program = compileProgram(vertex, fragment);
            inputUniform = GLES20.glGetUniformLocation(program, "InputBuffer");
            saturationUniform = GLES20.glGetUniformLocation(program, "saturation");
            contrastUniform = GLES20.glGetUniformLocation(program, "contrast");
            shadowsUniform = GLES20.glGetUniformLocation(program, "shadows");
            compressorUniform = GLES20.glGetUniformLocation(program, "compressor");
        }
        if (width == frameWidth && height == frameHeight && framebuffer != 0) return;
        deleteFrameResources();
        width = frameWidth;
        height = frameHeight;
        int[] names = new int[2];
        GLES20.glGenTextures(2, names, 0);
        inputTexture = names[0];
        outputTexture = names[1];
        allocateTexture(inputTexture);
        allocateTexture(outputTexture);
        int[] fbo = new int[1];
        GLES20.glGenFramebuffers(1, fbo, 0);
        framebuffer = fbo[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, outputTexture, 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Streamed post-pipeline framebuffer is incomplete");
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void allocateTexture(int texture) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
    }

    private void deleteFrameResources() {
        if (inputTexture != 0 || outputTexture != 0) {
            GLES20.glDeleteTextures(2, new int[]{inputTexture, outputTexture}, 0);
        }
        if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, new int[]{framebuffer}, 0);
        inputTexture = outputTexture = framebuffer = 0;
    }

    private static int compileProgram(String vertexSource, String fragmentSource) {
        int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int result = GLES20.glCreateProgram();
        GLES20.glAttachShader(result, vertex);
        GLES20.glAttachShader(result, fragment);
        GLES20.glLinkProgram(result);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, linked, 0);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        if (linked[0] == 0) {
            String message = GLES20.glGetProgramInfoLog(result);
            GLES20.glDeleteProgram(result);
            throw new IllegalStateException("Streamed pipeline link failed: " + message);
        }
        return result;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, "#version 300 es\n" + source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String message = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            Log.e(TAG, message);
            throw new IllegalStateException("Streamed pipeline shader compilation failed");
        }
        return shader;
    }
}
