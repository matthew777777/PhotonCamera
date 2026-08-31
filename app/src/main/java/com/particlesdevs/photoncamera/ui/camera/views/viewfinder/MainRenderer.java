package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import com.particlesdevs.photoncamera.util.Log;

import androidx.annotation.NonNull;

import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.circularbarlib.api.ManualModeConsole;
import com.particlesdevs.photoncamera.processing.live.RawSuperPixel;
import com.particlesdevs.photoncamera.processing.live.StreamedPostPipeline;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private int[] hTex;
    private final FloatBuffer pVertex;
    private final FloatBuffer pTexCoord;
    private final float[] mTexRotateMatrix = new float[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };

    private SurfaceTexture mSTexture;

    private boolean mGLInit = false;
    private boolean mUpdateST = false;
    private volatile boolean mMirrorPreview;
    private ColorLut pendingLut;
    private boolean colorLutUpdatePending;
    private ColorLut currentLut;
    private int colorLutTexture;
    private FloatBuffer colorLutUploadBuffer;
    private RawPreviewFrame pendingRawFrame;
    private boolean rawFrameUpdatePending;
    private int hProgram;
    private int downsampleProgram;
    private int downsampleFramebuffer;
    private int downsampleTexture;
    private int downsampleUvTransform;
    private int downsampleSampleStep;
    private float frameCropScaleX = 1.0f;
    private float frameCropScaleY = 1.0f;
    private int downsampleWidth;
    private int downsampleHeight;
    private int surfaceWidth;
    private int surfaceHeight;
    private ByteBuffer estimatorInput;
    private final AtomicBoolean estimatorBusy = new AtomicBoolean();
    private final ExecutorService estimatorExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ColorLutEstimator");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private static final long TIMESTAMP_TOLERANCE_NS = 3_000_000L;
    /** Debug: render the read-back ISP preview pixels instead of the camera preview. */
    private static final boolean DEBUG_ISP_PREVIEW = false;
    private int ispPreviewTexture;
    private int enableIspPreview;
    private final ColorLutEstimator colorLutEstimator = new ColorLutEstimator();
    private final StreamedPostPipeline streamedPostPipeline = new StreamedPostPipeline();
    private volatile float lastPreviewNodesMs;
    private long lutTimingWindowStartedNs;
    private long lutTotalUs;
    private int lutTimingSamples;

    private final GLPreview mView;
    private ManualModeConsole mManualModeConsole;

    public void setManualModeConsole(ManualModeConsole console) {
        this.mManualModeConsole = console;
    }

    MainRenderer(GLPreview view) {
        mView = view;
        pVertex = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        float[] vtmp = { 1.0f, -1.0f, -1.0f, -1.0f, 1.0f, 1.0f, -1.0f, 1.0f };
        pVertex.put(vtmp);
        pVertex.position(0);
        pTexCoord = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        float[] ttmp = { 1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f };
        pTexCoord.put(ttmp);
        pTexCoord.position(0);
        setOrientation(180);
    }

    public void onDrawFrame(GL10 unused) {
        if (!mGLInit)
            return;
        // GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        synchronized (this) {
            if (mUpdateST) {
                mSTexture.updateTexImage();
                mUpdateST = false;
            }
        }
        estimatePendingRawFrame();
        uploadPendingLut();
        bindColorLut();
        GLES20.glUniformMatrix4fv(uTexRotateMatrix, 1, false, mTexRotateMatrix, 0);
        int peakEnabled = getPeakEnabled();
        GLES20.glUniform1i(enablePeak, peakEnabled);
        GLES20.glUniform1i(mirror, mMirrorPreview ? 1 : 0);

        GLES20.glVertexAttribPointer(vPosition, 2, GLES20.GL_FLOAT, false, 4 * 2, pVertex);
        GLES20.glVertexAttribPointer(vTexCoord, 2, GLES20.GL_FLOAT, false, 4 * 2, pTexCoord);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        // GLES20.glFlush();
    }

    private int uTexRotateMatrix;
    private int vPosition;
    private int vTexCoord;
    private int enablePeak;
    private int mirror;
    private int enableColorLut;

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        deleteColorLutTexture();
        streamedPostPipeline.reset();
        downsampleProgram = 0;
        downsampleFramebuffer = 0;
        downsampleTexture = 0;
        downsampleWidth = 0;
        downsampleHeight = 0;
        ispPreviewTexture = 0;
        initTex();
        mSTexture = new SurfaceTexture(hTex[0]);
        mSTexture.setOnFrameAvailableListener(this);

        String vss_default = PhotonCamera.getAssetLoader().getString("shaders/preview/main_vs.glsl");
        String fss_default = PhotonCamera.getAssetLoader().getString("shaders/preview/main_fs.glsl");
        hProgram = loadShader(vss_default, fss_default);
        GLES20.glUseProgram(hProgram);
        uTexRotateMatrix = GLES20.glGetUniformLocation(hProgram, "uTexRotateMatrix");
        GLES20.glUniformMatrix4fv(uTexRotateMatrix, 1, false, mTexRotateMatrix, 0);
        vPosition = GLES20.glGetAttribLocation(hProgram, "vPosition");
        vTexCoord = GLES20.glGetAttribLocation(hProgram, "vTexCoord");
        enablePeak = GLES20.glGetUniformLocation(hProgram, "enablePeak");
        mirror = GLES20.glGetUniformLocation(hProgram, "mirror");
        enableColorLut = GLES20.glGetUniformLocation(hProgram, "enableColorLut");
        enableIspPreview = GLES20.glGetUniformLocation(hProgram, "enableIspPreview");
        GLES20.glUniform1i(GLES20.glGetUniformLocation(hProgram, "sTexture"), 0);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(hProgram, "colorLut"), 1);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(hProgram, "ispPreview"), 4);
        GLES20.glVertexAttribPointer(vPosition, 2, GLES20.GL_FLOAT, false, 4 * 2, pVertex);
        GLES20.glVertexAttribPointer(vTexCoord, 2, GLES20.GL_FLOAT, false, 4 * 2, pTexCoord);
        GLES20.glEnableVertexAttribArray(vPosition);
        GLES20.glEnableVertexAttribArray(vTexCoord);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(hProgram, "resolution"), mView.getWidth(), mView.getHeight());
        mGLInit = true;
        // A context recreation invalidates texture names, but not the CPU model.
        setColorLut(currentLut);
        mView.fireOnSurfaceTextureAvailable(mSTexture, 0, 0);
    }

    public void onSurfaceChanged(GL10 unused, int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
        GLES30.glViewport(0, 0, width, height);
    }

    public SurfaceTexture getmSTexture() {
        return mSTexture;
    }

    private void initTex() {
        hTex = new int[1];
        GLES20.glGenTextures(1, hTex, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, hTex[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    }

    private synchronized void setColorLut(ColorLut lut) {
        pendingLut = lut;
        colorLutUpdatePending = true;
    }

    private synchronized void uploadPendingLut() {
        if (!colorLutUpdatePending) return;
        ColorLut lut = pendingLut;
        pendingLut = null;
        colorLutUpdatePending = false;
        if (lut == null) { currentLut = null; deleteColorLutTexture(); return; }
        if (colorLutTexture == 0) {
            int[] name = new int[1]; GLES30.glGenTextures(1, name, 0); colorLutTexture = name[0];
        }
        if (colorLutUploadBuffer == null) colorLutUploadBuffer = ByteBuffer
                .allocateDirect(lut.rgb.length * Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
        colorLutUploadBuffer.clear(); colorLutUploadBuffer.put(lut.rgb).flip();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, colorLutTexture);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, ColorLut.SIZE,
                ColorLut.SIZE, ColorLut.SIZE, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, colorLutUploadBuffer);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        currentLut = lut;
    }

    private void bindColorLut() {
        GLES20.glUniform1i(enableIspPreview,
                DEBUG_ISP_PREVIEW && ispPreviewTexture != 0 ? 1 : 0);
        if (DEBUG_ISP_PREVIEW && ispPreviewTexture != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE4);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ispPreviewTexture);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        }
        boolean enabled = currentLut != null && colorLutTexture != 0;
        GLES20.glUniform1i(enableColorLut, enabled ? 1 : 0);
        if (!enabled) return;
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, colorLutTexture);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, hTex[0]);
    }

    private void deleteColorLutTexture() {
        if (colorLutTexture != 0) GLES30.glDeleteTextures(1, new int[]{colorLutTexture}, 0);
        colorLutTexture = 0;
    }

    public synchronized void setRawPreviewFrame(RawPreviewFrame frame) {
        if (pendingRawFrame != null) pendingRawFrame.close();
        pendingRawFrame = frame;
        rawFrameUpdatePending = true;
    }

    synchronized boolean shouldProcessRawPreviewFrame() {
        // No artificial frequency limit: accept every RAW frame whenever the
        // previous model has completed. Never queue an unpaired RAW target,
        // because its matching SurfaceTexture frame would be gone by then.
        return !estimatorBusy.get() && !rawFrameUpdatePending;
    }

    private synchronized void estimatePendingRawFrame() {
        if (!rawFrameUpdatePending) return;
        if (estimatorBusy.get()) return;
        RawPreviewFrame target = pendingRawFrame;
        if (target == null) {
            pendingRawFrame = null;
            rawFrameUpdatePending = false;
            setColorLut(null);
            return;
        }
        long previewTimestamp = mSTexture.getTimestamp();
        long timestampDelta = previewTimestamp - target.getTimestampNs();
        if (timestampDelta < -TIMESTAMP_TOLERANCE_NS) return; // Matching ISP frame has not arrived.
        if (timestampDelta > TIMESTAMP_TOLERANCE_NS) {
            // The SurfaceTexture has advanced beyond this RAW frame. Never fit
            // spatial coefficients from different moments.
            pendingRawFrame = null;
            rawFrameUpdatePending = false;
            target.close();
            return;
        }
        pendingRawFrame = null;
        rawFrameUpdatePending = false;
        try {
            int bytes = target.getWidth() * target.getHeight() * 4;
            if (estimatorInput == null || estimatorInput.capacity() != bytes) {
                estimatorInput = ByteBuffer.allocateDirect(bytes);
            }
            ByteBuffer output = target.pixels();
            long gpuNodesStarted = System.nanoTime();
            streamedPostPipeline.process(output, target.getWidth(), target.getHeight(),
                    surfaceWidth, surfaceHeight, target.getGains(), target.getToneCurve(),
                    target.getParameters());
            lastPreviewNodesMs = (System.nanoTime() - gpuNodesStarted) / 1_000_000f;
            ByteBuffer input = captureIspPreview(target.getWidth(), target.getHeight(), estimatorInput);
            if (DEBUG_ISP_PREVIEW) {
                uploadIspPreview(output, target.getWidth(), target.getHeight());
            }

            estimatorBusy.set(true);
            final ByteBuffer fitInput = estimatorInput;
            final ByteBuffer fitTarget = output;
            final int fitWidth = target.getWidth();
            final int fitHeight = target.getHeight();
            estimatorExecutor.execute(() -> {
                try {
                    ColorLut lut = colorLutEstimator.estimate(fitInput, fitTarget, fitWidth, fitHeight);
                    recordLutTiming(colorLutEstimator.getLastTimeUs());
                    setColorLut(lut);
                } catch (Exception error) {
                    Log.w("MainRenderer", "3D LUT worker failed: " + error.getMessage());
                } finally {
                    target.close();
                    estimatorBusy.set(false);
                    // Publish immediately; the next RAW callback may now start
                    // another timestamp-matched model.
                    mView.requestRender();
                }
            });
        } catch (Exception error) {
            target.close();
            estimatorBusy.set(false);
            Log.w("MainRenderer", "3D LUT preview estimate failed: " + error.getMessage());
        }
    }

    float getLastPreviewNodesMs() {
        return lastPreviewNodesMs;
    }

    private void recordLutTiming(long timeUs) {
        long now = System.nanoTime();
        if (lutTimingWindowStartedNs == 0) lutTimingWindowStartedNs = now;
        lutTotalUs += timeUs;
        lutTimingSamples++;
        if (now - lutTimingWindowStartedNs < 1_000_000_000L) return;
        Log.d("MainRenderer", String.format(java.util.Locale.US,
                "3D LUT 17^3 from %dx%d avg %.2f ms, %.1f fits/s",
                RawSuperPixel.OUTPUT_WIDTH, RawSuperPixel.OUTPUT_HEIGHT,
                lutTotalUs / (1000.0f * lutTimingSamples),
                lutTimingSamples * 1.0e9f / (now - lutTimingWindowStartedNs)));
        lutTimingWindowStartedNs = now;
        lutTotalUs = 0;
        lutTimingSamples = 0;
    }

    /** Debug: pushes the exact read-back pixels the estimator receives on screen. */
    private void uploadIspPreview(ByteBuffer pixels, int width, int height) {
        if (ispPreviewTexture == 0) {
            int[] texture = new int[1];
            GLES20.glGenTextures(1, texture, 0);
            ispPreviewTexture = texture[0];
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ispPreviewTexture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        pixels.position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    /**
     * Central-crop scales mapping the preview buffer's field of view onto the
     * RAW frame's aspect ratio. The wider dimension of the buffer is cropped so
     * scene geometry stays undistorted (e.g. a 16:9 stream is a center crop of
     * the 4:3 sensor, so its central 4:3 rect corresponds to a central 4:3
     * rect of the RAW frame, just at a smaller scale).
     */
    private void computeFrameCrop(int frameWidth, int frameHeight) {
        frameCropScaleX = 1.0f;
        frameCropScaleY = 1.0f;
        android.graphics.Point buffer = mView.cameraSize;
        if (buffer == null || buffer.x <= 0 || buffer.y <= 0) return;
        float frameAspect = (float) frameWidth / frameHeight;
        float bufferAspect = (float) buffer.x / buffer.y;
        if (bufferAspect > frameAspect) frameCropScaleX = frameAspect / bufferAspect;
        else frameCropScaleY = bufferAspect / frameAspect;
    }

    private ByteBuffer captureIspPreview(int width, int height, ByteBuffer destination) {
        ensureDownsampleTarget(width, height);
        computeFrameCrop(width, height);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, downsampleFramebuffer);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(downsampleProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, hTex[0]);
        int position = GLES20.glGetAttribLocation(downsampleProgram, "vPosition");
        int textureCoordinate = GLES20.glGetAttribLocation(downsampleProgram, "vTexCoord");
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 8, pVertex);
        GLES20.glVertexAttribPointer(textureCoordinate, 2, GLES20.GL_FLOAT, false, 8, pTexCoord);
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glEnableVertexAttribArray(textureCoordinate);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(downsampleProgram, "sTexture"), 0);
        GLES20.glUniform4f(downsampleUvTransform, frameCropScaleX, frameCropScaleY,
                (1.0f - frameCropScaleX) * 0.5f, (1.0f - frameCropScaleY) * 0.5f);
        GLES20.glUniform2f(downsampleSampleStep,
                frameCropScaleX / width / 4.0f, frameCropScaleY / height / 4.0f);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        destination.position(0);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                destination);
        destination.position(0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
        GLES20.glUseProgram(hProgram);
        return destination;
    }

    private void ensureDownsampleTarget(int width, int height) {
        if (downsampleFramebuffer != 0 && downsampleWidth == width && downsampleHeight == height) return;
        if (downsampleTexture != 0) GLES20.glDeleteTextures(1, new int[] {downsampleTexture}, 0);
        if (downsampleFramebuffer != 0) GLES20.glDeleteFramebuffers(1, new int[] {downsampleFramebuffer}, 0);
        if (downsampleProgram == 0) {
            // Samples the SurfaceTexture in plain sensor orientation (the display
            // path's 90-degree coordinate rotation must NOT be applied here).
            // OES textures sample with the image vertically flipped under identity
            // sampling; undoing it (instead of glReadPixels' bottom-up rows) makes
            // the readback buffer line up with the sensor-oriented RAW frame's
            // row/column order. uvTransform crops the preview's field of view to
            // the RAW frame's aspect ratio.
            String vertex = "in vec2 vPosition; in vec2 vTexCoord; out vec2 texCoord;"
                    + "uniform vec4 uvTransform;"
                    + "void main(){texCoord=vTexCoord.xy*uvTransform.xy+uvTransform.zw;"
                    + "texCoord.y=1.0-texCoord.y;"
                    + "gl_Position=vec4(vPosition,0.0,1.0);}";
            String fragment = "#extension GL_OES_EGL_image_external_essl3 : require\n"
                    + "precision mediump float; uniform samplerExternalOES sTexture;"
                    + "uniform vec2 sampleStep;"
                    + "in vec2 texCoord; out vec4 Output;"
                    + "void main(){vec4 sum=vec4(0.0);"
                    + "for(int y=0;y<4;y++)for(int x=0;x<4;x++){"
                    + "vec2 o=(vec2(x,y)-vec2(1.5))*sampleStep;"
                    + "sum+=texture(sTexture,texCoord+o);}Output=sum/16.0;}";
            downsampleProgram = loadShader(vertex, fragment);
            downsampleUvTransform = GLES20.glGetUniformLocation(downsampleProgram, "uvTransform");
            downsampleSampleStep = GLES20.glGetUniformLocation(downsampleProgram, "sampleStep");
        }
        int[] names = new int[1];
        GLES20.glGenTextures(1, names, 0);
        downsampleTexture = names[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, downsampleTexture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glGenFramebuffers(1, names, 0);
        downsampleFramebuffer = names[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, downsampleFramebuffer);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, downsampleTexture, 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("ISP downsample framebuffer is incomplete");
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        downsampleWidth = width;
        downsampleHeight = height;
    }

    public synchronized void onFrameAvailable(SurfaceTexture st) {
        mUpdateST = true;
        mView.requestRender();
    }

    private static String GetSupportedVersion() {
        return "#version 300 es";
    }

    private static int loadShader(String vss, String fss) {
        String SupportedVersion = GetSupportedVersion();
        vss = SupportedVersion + "\n #line 1\n" + vss;
        fss = SupportedVersion + "\n #line 1\n" + fss;
        int vshader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(vshader, vss);
        GLES20.glCompileShader(vshader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(vshader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e("Shader", "Could not compile vshader");
            Log.v("Shader", "Could not compile vshader:" + GLES20.glGetShaderInfoLog(vshader));
            GLES20.glDeleteShader(vshader);
            vshader = 0;
        }

        int fshader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fshader, fss);
        GLES20.glCompileShader(fshader);
        GLES20.glGetShaderiv(fshader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e("Shader", "Could not compile fshader");
            Log.v("Shader", "Could not compile fshader:" + GLES20.glGetShaderInfoLog(fshader));
            GLES20.glDeleteShader(fshader);
            fshader = 0;
        }

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vshader);
        GLES20.glAttachShader(program, fshader);
        GLES20.glLinkProgram(program);

        return program;
    }

    public void setMirror(boolean mirrorPreview) {
        mMirrorPreview = mirrorPreview;
    }

    private int getPeakEnabled() {
        int focusPeakSetting = PhotonCamera.getSettings().focusPeak;
        if (focusPeakSetting == 1) {
            return 1; // On
        } else if (focusPeakSetting == 2) {
            // Auto: show peaking when manual focus mode is active OR when focus parameter
            // is selected via UI
            if (mManualModeConsole != null) {
                return (mManualModeConsole.isManualFocusModeActive() || mManualModeConsole.isFocusParameterSelected())
                        ? 1
                        : 0;
            }
            return 0;
        }
        return 0; // Off
    }

    public void setOrientation(int or) {
        android.opengl.Matrix.setRotateM(mTexRotateMatrix, 0, or, 0f, 0f, 1f);
    }

    public void setTransform(@NonNull android.graphics.Matrix matrix) {
        Log.d("MainRenderer", "setTransform: " + matrix + " " + Arrays.toString(mTexRotateMatrix));
        matrix.getValues(mTexRotateMatrix);
    }

    RectF mLastImageRect = new RectF();
    RectF inputRect = new RectF();

    public void scale(int in_width, int in_height, int out_width, int out_height, int rotation) {
        int difw = out_width - in_width;
        int difh = out_height - in_height;

        inputRect.left = (int) (difw / 2);
        inputRect.top = (int) (difh / 2);
        inputRect.right = in_width;
        inputRect.bottom = in_height;
        if (mLastImageRect != inputRect) {
            GLES20.glViewport((int) inputRect.left, (int) inputRect.top, (int) inputRect.width(),
                    (int) inputRect.height());

            mLastImageRect.set(inputRect);
        }

    }
}
