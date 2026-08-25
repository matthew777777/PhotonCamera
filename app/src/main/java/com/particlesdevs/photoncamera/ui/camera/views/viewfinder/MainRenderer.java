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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

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
    private BilateralGrid pendingGrid;
    private boolean bilateralGridUpdatePending;
    private BilateralGrid currentGrid;
    private final int[] bilateralTextures = new int[3];
    private int bguBlend;
    private RawPreviewFrame pendingRawFrame;
    private ByteBuffer pendingRawCopy;
    private boolean rawFrameUpdatePending;
    private int hProgram;
    private int downsampleProgram;
    private int downsampleFramebuffer;
    private int downsampleTexture;
    private int downsampleUvTransform;
    private float frameCropScaleX = 1.0f;
    private float frameCropScaleY = 1.0f;
    private int downsampleWidth;
    private int downsampleHeight;
    private int surfaceWidth;
    private int surfaceHeight;
    private ByteBuffer downsamplePixels;
    /** Debug: render the read-back ISP preview pixels instead of the camera preview. */
    private static final boolean DEBUG_ISP_PREVIEW = false;
    private int ispPreviewTexture;
    private int enableIspPreview;
    private final BilateralGridEstimator bilateralGridEstimator = new BilateralGridEstimator(
            BilateralGridEstimator.Options.previewDefaults());

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
        uploadPendingGrid();
        bindBilateralGrid();
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
    private int enableBilateralGrid;
    private int bilateralGridSize;

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        deleteBilateralTextures();
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
        enableBilateralGrid = GLES20.glGetUniformLocation(hProgram, "enableBilateralGrid");
        bilateralGridSize = GLES20.glGetUniformLocation(hProgram, "bilateralGridSize");
        enableIspPreview = GLES20.glGetUniformLocation(hProgram, "enableIspPreview");
        GLES20.glUniform1i(GLES20.glGetUniformLocation(hProgram, "sTexture"), 0);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(hProgram, "bilateralGridR"), 1);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(hProgram, "bilateralGridG"), 2);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(hProgram, "bilateralGridB"), 3);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(hProgram, "bilateralGridRPrev"), 5);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(hProgram, "bilateralGridGPrev"), 6);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(hProgram, "bilateralGridBPrev"), 7);
        bguBlend = GLES20.glGetUniformLocation(hProgram, "bguBlend");
        GLES20.glUniform1i(GLES20.glGetUniformLocation(hProgram, "ispPreview"), 4);
        GLES20.glVertexAttribPointer(vPosition, 2, GLES20.GL_FLOAT, false, 4 * 2, pVertex);
        GLES20.glVertexAttribPointer(vTexCoord, 2, GLES20.GL_FLOAT, false, 4 * 2, pTexCoord);
        GLES20.glEnableVertexAttribArray(vPosition);
        GLES20.glEnableVertexAttribArray(vTexCoord);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(hProgram, "resolution"), mView.getWidth(), mView.getHeight());
        mGLInit = true;
        // A context recreation invalidates texture names, but not the CPU model.
        setBilateralGrid(currentGrid);
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

    public synchronized void setBilateralGrid(BilateralGrid grid) {
        pendingGrid = grid;
        bilateralGridUpdatePending = true;
    }

    private synchronized void uploadPendingGrid() {
        if (!bilateralGridUpdatePending) {
            return;
        }
        BilateralGrid grid = pendingGrid;
        pendingGrid = null;
        bilateralGridUpdatePending = false;
        if (grid == null) {
            currentGrid = null;
            deleteBilateralTextures();
            return;
        }

        deleteBilateralTextures();
        GLES30.glGenTextures(3, bilateralTextures, 0);
        for (int row = 0; row < 3; row++) {
            float[] values = grid.row(row).values;
            FloatBuffer data = ByteBuffer.allocateDirect(values.length * Float.BYTES)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            data.put(values).position(0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1 + row);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, bilateralTextures[row]);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA16F,
                    grid.getWidth(), grid.getHeight(), grid.getDepth(), 0,
                    GLES30.GL_RGBA, GLES30.GL_FLOAT, data);
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        currentGrid = grid;
    }

    private void bindBilateralGrid() {
        GLES20.glUniform1i(enableIspPreview,
                DEBUG_ISP_PREVIEW && ispPreviewTexture != 0 ? 1 : 0);
        if (DEBUG_ISP_PREVIEW && ispPreviewTexture != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE4);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ispPreviewTexture);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        }
        boolean enabled = currentGrid != null && bilateralTextures[0] != 0;
        GLES20.glUniform1i(enableBilateralGrid, enabled ? 1 : 0);
        if (!enabled) {
            return;
        }
        GLES20.glUniform3f(bilateralGridSize, currentGrid.getWidth(),
                currentGrid.getHeight(), currentGrid.getDepth());
        GLES20.glUniform1f(bguBlend, 1.0f);
        for (int row = 0; row < 3; row++) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1 + row);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, bilateralTextures[row]);
            // Interpolation is disabled; the prev samplers alias the current grid
            // so the shader's mix() is a no-op.
            GLES20.glActiveTexture(GLES20.GL_TEXTURE5 + row);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, bilateralTextures[row]);
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, hTex[0]);
    }

    private void deleteBilateralTextures() {
        if (bilateralTextures[0] != 0) {
            GLES30.glDeleteTextures(3, bilateralTextures, 0);
            Arrays.fill(bilateralTextures, 0);
        }
    }

    public synchronized void setRawPreviewFrame(RawPreviewFrame frame) {
        if (frame == null) {
            pendingRawFrame = null;
        } else {
            int bytes = frame.getWidth() * frame.getHeight() * 4;
            if (pendingRawCopy == null || pendingRawCopy.capacity() != bytes) {
                pendingRawCopy = ByteBuffer.allocateDirect(bytes);
            }
            ByteBuffer source = frame.pixels().duplicate();
            source.position(0).limit(bytes);
            pendingRawCopy.position(0);
            pendingRawCopy.put(source).position(0);
            pendingRawFrame = new RawPreviewFrame(frame.getWidth(), frame.getHeight(), pendingRawCopy);
        }
        rawFrameUpdatePending = true;
    }

    private synchronized void estimatePendingRawFrame() {
        if (!rawFrameUpdatePending) return;
        RawPreviewFrame target = pendingRawFrame;
        pendingRawFrame = null;
        rawFrameUpdatePending = false;
        if (target == null) {
            setBilateralGrid(null);
            return;
        }
        try {
            ByteBuffer input = captureIspPreview(target.getWidth(), target.getHeight());
            ByteBuffer output = target.pixels();
            if (DEBUG_ISP_PREVIEW) {
                uploadIspPreview(output, target.getWidth(), target.getHeight());
            }

            input.position(0);
            output.position(0);
            setBilateralGrid(bilateralGridEstimator.estimateRgba8(input, output,
                    target.getWidth(), target.getHeight()));
        } catch (Exception error) {
            Log.w("MainRenderer", "BGU preview estimate failed: " + error.getMessage());
        }
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

    private ByteBuffer captureIspPreview(int width, int height) {
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
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        downsamplePixels.position(0);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                downsamplePixels);
        downsamplePixels.position(0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
        GLES20.glUseProgram(hProgram);
        return downsamplePixels;
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
                    + "in vec2 texCoord; out vec4 Output;"
                    + "void main(){Output=texture(sTexture,texCoord);}";
            downsampleProgram = loadShader(vertex, fragment);
            downsampleUvTransform = GLES20.glGetUniformLocation(downsampleProgram, "uvTransform");
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
        downsamplePixels = ByteBuffer.allocateDirect(width * height * 4);
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
