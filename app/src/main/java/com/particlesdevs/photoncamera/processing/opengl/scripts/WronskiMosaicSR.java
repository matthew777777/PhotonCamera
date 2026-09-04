package com.particlesdevs.photoncamera.processing.opengl.scripts;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.opengl.GLCoreBlockProcessing;
import com.particlesdevs.photoncamera.processing.opengl.GLDrawParams;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLOneScript;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.GLUtils;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_NEAREST;

/**
 * Experimental Wronski-style multi-frame super-resolution whose reconstruction target is
 * a synthetic Bayer CFA instead of full RGB.  The output is deliberately a derived mosaic:
 * every output site stores only the reconstructed R/G/B sample required by the camera CFA pattern,
 * allowing a later RAW developer to choose its own demosaic algorithm.
 *
 * The alignment stage reuses PhotonCamera's sub-pixel PyramidAlignment.  Fusion follows the
 * Wronski/IPOL architecture: locally steered anisotropic kernels, noise-aware robustness,
 * per-sample weighted accumulation, and normalization.  At sqrt(2) linear scale, a 12 MP
 * input becomes approximately 24 MP; output dimensions are forced even to preserve the Bayer phase.
 */
public final class WronskiMosaicSR extends GLOneScript {
    private static final String TAG = "WronskiMosaicSR";
    public static final float DEFAULT_SCALE = 1.41421356237f;

    public Parameters parameters;
    /** Spatially adaptive KernelNet reconstruction parameters exported by ESD4D: RGBA = (s1,s2,rho,1). */
    public FloatBuffer kernelParams;
    public Point kernelParamsSize;
    private final ArrayList<ImageFrame> images;
    private final GLProg glProg;
    private final GLUtils glUtils;
    private final Point inputSize;
    private final Point outputSize;

    public WronskiMosaicSR(Point inputSize, ArrayList<ImageFrame> images) {
        this(inputSize, images, DEFAULT_SCALE);
    }

    public WronskiMosaicSR(Point inputSize, ArrayList<ImageFrame> images, float scale) {
        super(computeOutputSize(inputSize, scale),
                new GLCoreBlockProcessing(computeOutputSize(inputSize, scale),
                        new GLFormat(GLFormat.DataType.UNSIGNED_16, 1),
                        GLDrawParams.Allocate.Direct), "", "WronskiMosaicSR", false);
        this.inputSize = new Point(inputSize);
        this.outputSize = computeOutputSize(inputSize, scale);
        this.images = images;
        this.glProg = glOne.glProgram;
        this.glUtils = new GLUtils(glProg);
    }

    public static Point computeOutputSize(Point in, float scale) {
        int w = nearestEven(in.x * scale);
        int h = nearestEven(in.y * scale);
        return new Point(Math.max(2, w), Math.max(2, h));
    }

    private static int nearestEven(float value) {
        int rounded = Math.round(value);
        if ((rounded & 1) == 0) return rounded;
        int lo = rounded - 1;
        int hi = rounded + 1;
        return Math.abs(value - lo) <= Math.abs(hi - value) ? lo : hi;
    }

    public Point getOutputSize() {
        return new Point(outputSize);
    }

    @Override public void Compile() {}

    @Override
    public void Run() {
        if (parameters == null) throw new IllegalStateException("parameters not set");
        if (images == null || images.size() < 2) throw new IllegalArgumentException("MFSR requires a burst");

        final long t0 = System.currentTimeMillis();
        final float scale = (float) outputSize.x / (float) inputSize.x;
        final Point rawHalf = new Point((inputSize.x + 1) / 2, (inputSize.y + 1) / 2);

        Point alignmentOutputSize = new Point(parameters.alignmentSize.x * parameters.tilesX,
                parameters.alignmentSize.y * ((images.size() - 1) / parameters.tilesX + 1));
        PyramidAlignment alignment = new PyramidAlignment(alignmentOutputSize, images, glProg, glUtils, this);
        alignment.parameters = parameters;
        alignment.Run();
        GLTexture alignmentTex = alignment.Result;

        // Mali/GLES requires every image2D to be explicitly readonly or writeonly.
        // Bind the same RGBA16F storage through two qualified image units. Each invocation
        // reads its previous (num,den) value and writes only that same output pixel; computeAuto()
        // inserts a full shader barrier + glFinish between burst frames. This keeps one ~25 MP
        // accumulator allocation rather than two huge ping-pong textures.
        GLFormat accFmt = new GLFormat(GLFormat.DataType.FLOAT_16, 4);
        GLTexture accumulator = new GLTexture(outputSize, accFmt, null, GL_NEAREST, GL_CLAMP_TO_EDGE);

        if (kernelParams == null || kernelParamsSize == null || kernelParamsSize.x <= 0 || kernelParamsSize.y <= 0) {
            accumulator.close();
            alignment.close();
            throw new IllegalStateException("KernelNet reconstruction parameter map unavailable; refusing fake/fallback kernel for Mosaic SR");
        }
        FloatBuffer kernelUpload = kernelParams.duplicate();
        kernelUpload.rewind();
        GLTexture kernelMap = new GLTexture(kernelParamsSize,
                new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        kernelMap.loadData(kernelUpload);
        Log.d(TAG, "Using ESD4D KernelNet reconstruction map " + kernelParamsSize.x + "x" + kernelParamsSize.y
                + " (s1,s2,rho) for spatially adaptive MFSR kernels");

        GLTexture baseRaw = new GLTexture(inputSize, new GLFormat(GLFormat.DataType.UNSIGNED_16, 1),
                images.get(0).buffer, GL_NEAREST, GL_CLAMP_TO_EDGE);
        GLTexture frameRaw = new GLTexture(inputSize, new GLFormat(GLFormat.DataType.UNSIGNED_16, 1),
                null, GL_NEAREST, GL_CLAMP_TO_EDGE);

        float[] black = new float[]{parameters.blackLevel[0], parameters.blackLevel[1],
                parameters.blackLevel[2], parameters.blackLevel[3]};

        // Reference first, then all secondary frames. Exposure normalization follows the existing HDRX pair.
        for (int f = 0; f < images.size(); ++f) {
            if (f == 0) frameRaw.loadData(images.get(0).buffer);
            else frameRaw.loadData(images.get(f).buffer);

            glProg.setLayout(8, 8, 1);
            glProg.useAssetProgram("merge/wronski_mosaic_accumulate", true);
            glProg.setTexture("baseRaw", baseRaw);
            glProg.setTexture("frameRaw", frameRaw);
            glProg.setTexture("alignmentTexture", alignmentTex);
            glProg.setTexture("kernelMap", kernelMap);
            glProg.setTextureCompute("prevAccumulator", accumulator, false);
            glProg.setTextureCompute("outAccumulator", accumulator, true);
            glProg.setVar("rawSize", inputSize);
            glProg.setVar("rawHalf", rawHalf);
            glProg.setVar("alignmentSize", parameters.alignmentSize);
            Point shift = f == 0 ? new Point(0, 0) : PyramidAlignment.alignmentShift(parameters, f);
            glProg.setVar("alignmentShift", shift);
            glProg.setVar("tileSize", parameters.tile);
            glProg.setVar("cfaPattern", parameters.cfaPattern);
            glProg.setVar("isBase", f == 0 ? 1 : 0);
            glProg.setVar("scale", scale);
            glProg.setVar("whiteLevel", (float) parameters.whiteLevel);
            glProg.setVar("blackLevel", black);
            glProg.setVar("exposure", 1.0f / Math.max(images.get(f).pair.layerMpy, 1e-6f));
            glProg.setVar("robustnessStrength", 0.12f);
            glProg.setVar("kernelDetail", 0.72f);
            glProg.setVar("kernelDenoise", 1.35f);
            // KernelNet is the spatially adaptive denoising/reconstruction kernel network.
            // Its learned (s1,s2,rho) covariance is used directly for SR reconstruction;
            // sub-pixel coverage from the aligned burst is what makes super-resolution emerge.
            glProg.setVar("kernelNetStrength", 1.0f);
            glProg.computeAuto(outputSize, 1);

        }

        // Mali-safe finalization. Avoid r16ui/uimage2D compute image stores: several Mali
        // GLES 3.1 drivers reject that layout even though R16UI render targets are supported.
        glProg.useAssetProgram("merge/wronski_mosaic_finalize", false);
        glProg.setTexture("accumulator", accumulator);
        glProg.setVar("whiteLevel", 65535.0f);
        glOne.glProcessing.drawBlocksToOutput();
        Output = glOne.glProcessing.mOutBuffer;
        Output.rewind();
        baseRaw.close();
        frameRaw.close();
        kernelMap.close();
        accumulator.close();
        alignment.close(); // closes alignment.Result
        Log.d(TAG, "Reconstructed " + inputSize.x + "x" + inputSize.y + " -> "
                + outputSize.x + "x" + outputSize.y + " from " + images.size() + " frames in "
                + (System.currentTimeMillis() - t0) + " ms");
    }
}
