package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;

import java.util.ArrayList;

/**
 * Memory-bounded GPU local-Laplacian contrast filter.  It uses six luminance
 * anchors and a remapping curve, but evaluates the remap on the Gaussian
 * levels instead of retaining six complete remapped pyramids.  That keeps the
 * additional storage near one single-channel pyramid, which is important for
 * full-resolution phone RAWs.
 */
public class LocalLaplacian extends Node {
    private static final int MAX_LEVELS = 8;

    public LocalLaplacian() {
        super("", "LocalLaplacian");
    }

    @Tunable(title = "Enable", description = "Enable Local Laplacian Filter",
            category = "LLF", min = 0, max = 1, defaultValue = 1, step = 1)
    boolean enabled;

    @Tunable(title = "Detail", description = "Increase or remove local contrast",
            category = "LLF", min = -1.0f, max = 2.0f, defaultValue = 0.25f, step = 0.05f)
    float detail;

    @Tunable(title = "Highlights", description = "Local contrast slope in highlights; lower values compress highlights",
            category = "LLF", min = 0.0f, max = 2.0f, defaultValue = 0.0f, step = 0.05f)
    float highlights;

    @Tunable(title = "Shadows", description = "Local contrast slope in shadows; lower values lift shadows",
            category = "LLF", min = 0.0f, max = 2.0f, defaultValue = 0.0f, step = 0.05f)
    float shadows;

    @Tunable(title = "Mid-tone Range", description = "Width of the local-contrast region",
            category = "LLF", min = 0.01f, max = 1.0f, defaultValue = 0.5f, step = 0.01f)
    float midtone;

    @Override
    public void Compile() {
    }

    private GLTexture downsampleLuma(GLTexture input, boolean rgbInput) {
        Point size = new Point(Math.max(1, (input.mSize.x + 1) / 2),
                Math.max(1, (input.mSize.y + 1) / 2));
        GLTexture output = new GLTexture(size, new GLFormat(GLFormat.DataType.FLOAT_16));
        glProg.setDefine("INPUT_RGB", rgbInput ? 1 : 0);
        glProg.useAssetProgram("local_laplacian/downsample");
        glProg.setTexture("InputBuffer", input);
        glProg.setVar("inputSize", input.mSize);
        glProg.drawBlocks(output);
        glProg.closed = true;
        return output;
    }

    @Override
    public void Run() {
        final GLTexture input = previousNode.WorkingTexture;
        if (!enabled || input.mSize.x < 2 || input.mSize.y < 2) {
            WorkingTexture = input;
            glProg.closed = true;
            return;
        }

        // gaussian[0] is represented by the RGB input itself.  The remaining
        // levels are single-channel luminance textures.
        ArrayList<GLTexture> gaussian = new ArrayList<>();
        gaussian.add(null);
        GLTexture levelInput = input;
        boolean rgbInput = true;
        while (gaussian.size() <= MAX_LEVELS
                && (levelInput.mSize.x > 2 || levelInput.mSize.y > 2)) {
            GLTexture next = downsampleLuma(levelInput, rgbInput);
            gaussian.add(next);
            levelInput = next;
            rgbInput = false;
        }

        int last = gaussian.size() - 1;
        GLTexture reconstructed = gaussian.get(last);

        for (int level = last - 1; level >= 0; level--) {
            boolean finest = level == 0;
            GLTexture fine = finest ? input : gaussian.get(level);
            GLTexture coarse = gaussian.get(level + 1);
            GLTexture output = finest
                    ? basePipeline.getMain()
                    : new GLTexture(fine.mSize, new GLFormat(GLFormat.DataType.FLOAT_16));

            glProg.setDefine("FINE_RGB", finest ? 1 : 0);
            glProg.setDefine("FINAL_OUTPUT", finest ? 1 : 0);
            glProg.useAssetProgram("local_laplacian/reconstruct");
            glProg.setTexture("FineBuffer", fine);
            glProg.setTexture("CoarseBuffer", coarse);
            glProg.setTexture("ReconstructedBuffer", reconstructed);
            glProg.setVar("fineSize", fine.mSize);
            glProg.setVar("coarseSize", coarse.mSize);
            glProg.setVar("sigma", Math.max(midtone, 0.001f));
            glProg.setVar("shadows", shadows);
            glProg.setVar("highlights", highlights);
            glProg.setVar("clarity", detail);
            glProg.drawBlocks(output);
            glProg.closed = true;

            if (reconstructed != coarse) reconstructed.close();
            coarse.close();
            reconstructed = output;
        }

        WorkingTexture = reconstructed;
    }
}
