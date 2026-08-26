package com.particlesdevs.photoncamera.processing.live;

import android.graphics.Point;
import android.opengl.GLES20;

import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLInterface;
import com.particlesdevs.photoncamera.processing.opengl.GLBasePipeline;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.processing.render.Parameters;

import java.nio.ByteBuffer;

/**
 * Persistent OpenGL post pipeline for the low-resolution SuperPixel stream.
 * Node-driven like the one-shot capture
 * {@link com.particlesdevs.photoncamera.processing.opengl.postpipeline.PostPipeline},
 * but it runs on the viewfinder's existing GL context and retains its
 * program/textures across frames instead of building a per-shot
 * GLCoreBlockProcessing context. Histogram analysis and the dynamic GTM curve
 * are produced by native SuperPixel; StreamedColor applies it together with
 * the matrix color chain and StreamedInitial applies the tone controls.
 */
public final class StreamedPostPipeline extends GLBasePipeline {
    private static final String TAG = "StreamedPostPipeline";
    private GLProg program;
    public GLTexture inputTexture;
    public GLTexture outputTexture;
    private int width;
    private int height;
    private GLTexture gainMapTexture;
    private Point gainMapSize;
    private int gainMapLogState;
    private GLTexture toneCurveTexture;
    /** Texture holding this frame's result; outputTexture after the final
     * fragment draw, or a pass-through node's WorkingTexture otherwise. */
    private GLTexture resultTexture;
    /** Per-frame white-balance gains (R, G, B), produced by RawSuperPixel. */
    public final float[] gains = {1.f, 1.f, 1.f};

    public StreamedPostPipeline() {
        super("StreamedPostPipeline");
    }

    /** Called after a new GL context is current. Names from the previous
     * context are already invalid, so simply forget them. */
    public void reset() {
        Nodes.clear();
        try {
            if (inputTexture != null) inputTexture.close();
            if (main1 != null) main1.close();
            if (outputTexture != null) outputTexture.close();
            if (gainMapTexture != null) gainMapTexture.close();
            if (toneCurveTexture != null) toneCurveTexture.close();
        } catch (Exception ignored) {
        }
        inputTexture = main1 = outputTexture = gainMapTexture = toneCurveTexture = null;
        resultTexture = null;
        gainMapSize = null;
        gainMapLogState = 0;
        program = null;
        glint = null;
        width = height = 0;
    }

    @Override
    public void close() {
        reset();
    }

    // Per-frame stream: skip the GLBasePipeline "Node:... elapsed" logs.
    @Override
    public void startTimeMeasure() {}

    @Override
    public void endTimeMeasure(String Name) {}

    /**
     * Processes tightly packed linear SuperPixel RGBA8 and writes the
     * gamma-encoded result back into that same buffer. {@code parameters} is
     * the fully filled capture-style Parameters used for matrix color
     * correction; may be null to fall back to plain white-balance gains.
     * {@code toneCurve} is the 256-entry histogram GTM curve produced by the
     * native SuperPixel pass for this frame.
     */
    public ByteBuffer process(ByteBuffer pixels, int frameWidth, int frameHeight,
                              int restoreWidth, int restoreHeight, float[] whiteBalanceGains,
                              ByteBuffer toneCurve, Parameters parameters) {
        if (pixels == null || !pixels.isDirect()
                || pixels.capacity() < frameWidth * frameHeight * 4
                || toneCurve == null || !toneCurve.isDirect()
                || toneCurve.capacity() < 256 * Float.BYTES) {
            throw new IllegalArgumentException("Streamed pipeline requires direct image and curve buffers");
        }
        System.arraycopy(whiteBalanceGains, 0, gains, 0, 3);
        mParameters = parameters;
        mSettings = PhotonCamera.getSettings();
        workSize = new Point(frameWidth, frameHeight);
        ensureResources(frameWidth, frameHeight);
        glint.parameters = parameters;

        pixels.position(0);
        inputTexture.loadData(pixels);
        toneCurve.position(0);
        toneCurveTexture.loadData(toneCurve);

        BuildDefaultPipeline();
        runStreamed();

        // runStreamed left the result texture bound as the readback target.
        pixels.position(0);
        resultTexture.textureBuffer(new GLFormat(GLFormat.DataType.SIMPLE_8, 4), pixels);
        pixels.position(0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, restoreWidth, restoreHeight);
        return pixels;
    }

    /**
     * Lens shading map texture for the streamed pipeline. Re-uploaded every
     * frame (the map is tiny), reallocated only when its dimensions change.
     * Falls back to a 1x1 identity map when Parameters has no gain map.
     */
    GLTexture ensureGainMap(com.particlesdevs.photoncamera.processing.render.Parameters params) {
        float[] map = params != null && params.gainMap != null ? params.gainMap
                : new float[]{1.f, 1.f, 1.f, 1.f};
        Point size = params != null && params.mapSize != null ? params.mapSize : new Point(1, 1);
        boolean active = params != null && params.hasGainMap && map.length >= 4
                && (size.x > 1 || size.y > 1);
        int mapState = active ? 2 : 1;
        if (gainMapLogState != mapState) {
            com.particlesdevs.photoncamera.util.Log.d(TAG,
                    "Lens shading map " + (active ? "active " : "unavailable ")
                            + size.x + "x" + size.y);
            gainMapLogState = mapState;
        }
        if (gainMapTexture == null || gainMapSize == null || !gainMapSize.equals(size)) {
            if (gainMapTexture != null) {
                try { gainMapTexture.close(); } catch (Exception ignored) {}
            }
            gainMapSize = new Point(size);
            gainMapTexture = new GLTexture(gainMapSize,
                    new GLFormat(GLFormat.DataType.FLOAT_16, 4),
                    com.particlesdevs.photoncamera.util.BufferUtils.getFrom(map),
                    GLES20.GL_LINEAR, GLES20.GL_CLAMP_TO_EDGE);
        } else {
            gainMapTexture.loadData(
                    com.particlesdevs.photoncamera.util.BufferUtils.getFrom(map));
        }
        return gainMapTexture;
    }

    private void BuildDefaultPipeline() {
        add(new StreamedColor());
        add(new StreamedInitial());
    }

    GLTexture currentToneCurve() {
        return toneCurveTexture;
    }

    /**
     * UV transform mapping the streamed output into the 4:3 crop the preview
     * shows inside the full raw frame the lens shading map covers.
     */
    float[] gainMapTransform(Parameters params) {
        float cropScaleX = 1.0f;
        float cropScaleY = 1.0f;
        if (params != null && params.rawSize != null
                && params.rawSize.x > 0 && params.rawSize.y > 0) {
            int cropWidth = Math.min(params.rawSize.x, params.rawSize.y * 4 / 3) & ~1;
            int cropHeight = Math.min(params.rawSize.y, params.rawSize.x * 3 / 4) & ~1;
            cropScaleX = (float) cropWidth / params.rawSize.x;
            cropScaleY = (float) cropHeight / params.rawSize.y;
        }
        return new float[]{cropScaleX, cropScaleY,
                (1.0f - cropScaleX) * 0.5f, (1.0f - cropScaleY) * 0.5f};
    }

    private void ensureToneResources() {
        if (toneCurveTexture != null) return;
        toneCurveTexture = new GLTexture(new Point(256, 1),
                new GLFormat(GLFormat.DataType.FLOAT_16), null,
                GLES20.GL_LINEAR, GLES20.GL_CLAMP_TO_EDGE);
    }

    /** Like GLBasePipeline.runAll(), minus the GLCoreBlockProcessing output pass. */
    private void runStreamed() {
        for (int i = 0; i < Nodes.size(); i++) {
            Node node = Nodes.get(i);
            node.mProp = mProp;
            node.BeforeCompile();
            node.Compile();
            node.BeforeRun();
            startTimeMeasure();
            node.Run();
            endTimeMeasure(node.Name);
            if (i != Nodes.size() - 1) {
                drawProgramTexture(node);
            }
            node.AfterRun();
        }
        Node last = Nodes.get(Nodes.size() - 1);
        if (last.OwnTexture) {
            drawSinglePass(last.GetProgTex());
            resultTexture = outputTexture;
        } else {
            // The pipeline ends on a pass-through node (e.g. a compute node
            // writing via imageStore): its texture already holds the result,
            // so bind it as the readback target instead of fragment-drawing
            // with a vertex-less compute program.
            last.WorkingTexture.BufferLoad();
            resultTexture = last.WorkingTexture;
        }
        Nodes.clear();
    }

    private void drawProgramTexture(Node node) {
        // Pass-through node: no new texture was produced.
        if (!node.OwnTexture) return;
        if (!glint.glProgram.closed) {
            drawSinglePass(node.GetProgTex());
            glint.glProgram.closed = true;
        }
    }

    /**
     * Single-pass full-frame draw. Unlike GLProg.drawBlocks() (tiled for the
     * ~50 MP capture buffers via GLDrawParams.TileSize viewports), the
     * streamed textures are tiny (512x384) and render in one viewport+draw.
     */
    private void drawSinglePass(GLTexture texture) {
        texture.BufferLoad();
        GLES20.glViewport(0, 0, texture.mSize.x, texture.mSize.y);
        glint.glProgram.draw();
    }

    private void ensureResources(int frameWidth, int frameHeight) {
        if (glint == null || program == null) {
            program = new GLProg();
            glint = new GLInterface(program);
        }
        if (inputTexture != null && main1 != null
                && width == frameWidth && height == frameHeight) {
            ensureToneResources();
            return;
        }
        try {
            if (inputTexture != null) inputTexture.close();
            if (main1 != null) main1.close();
            if (outputTexture != null) outputTexture.close();
        } catch (Exception ignored) {
        }
        width = frameWidth;
        height = frameHeight;
        GLFormat format = new GLFormat(GLFormat.DataType.SIMPLE_8, 4);
        inputTexture = new GLTexture(new Point(frameWidth, frameHeight), format);
        main1 = new GLTexture(new Point(frameWidth, frameHeight), format);
        outputTexture = new GLTexture(new Point(frameWidth, frameHeight), format);
        ensureToneResources();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }
}
