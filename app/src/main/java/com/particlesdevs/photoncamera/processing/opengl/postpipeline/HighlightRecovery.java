package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.util.Log;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;
import java.util.ArrayList;
import java.util.List;

/**
 * HighlightRecovery - "Inpaint Opposed" reconstruction, ported to run fully
 * on GPU.
 */
public class HighlightRecovery extends Node {
    public HighlightRecovery() {
        super("", "HighlightRecovery");
    }

    @Override
    public void Compile() {}

    @Tunable(
        title = "Clip Threshold",
        category = "Highlight Recovery",
        subTab = "Experimental Pipeline",
        min = 0.90f,
        max = 1.0f,
        defaultValue = 0.98f,
        description = "Threshold above which pixels are considered clipped"
    )
    float clipThreshold = 0.98f;

    @Tunable(
        title = "Candidate Brightness Floor",
        category = "Highlight Recovery",
        subTab = "Experimental Pipeline",
        min = 0.0f,
        max = 1.0f,
        defaultValue = 0.2f,
        description = "Ignore candidate photosites darker than this value"
    )
    float chromaSampleMin = 0.2f;

    @Tunable(
        title = "CFA Pattern Override",
        category = "Highlight Recovery",
        subTab = "Experimental Pipeline",
        min = -1,
        max = 3,
        defaultValue = -1,
        step = 1,
        description = "Bayer layout: -1=Auto 0=RGGB 1=BGGR 2=GRBG 3=GBRG"
    )
    int cfaPatternOverride = -1;

    @Tunable(
        title = "Debug Mask",
        category = "Highlight Recovery",
        subTab = "Experimental Pipeline",
        min = 0,
        max = 1,
        defaultValue = 0,
        step = 1,
        description = "Show pixels affected by highlight recovery"
    )
    int debugMask = 0;

    @Tunable(
        title = "Pass Through",
        category = "Highlight Recovery",
        subTab = "Experimental Pipeline",
        min = 0,
        max = 1,
        defaultValue = 0,
        step = 1,
        description = "Disable algorithm and pass input directly to output"
    )
    public int passThrough = 0;

    private final List<GLTexture> scratchTextures = new ArrayList<>();

    @Override
    public void Run() {
        Log.d(Name, "Run() started, passThrough=" + passThrough);
        if (previousNode.WorkingTexture == null) {
            Log.e(Name, "Input texture is NULL!");
            return;
        }

        if (passThrough == 1) {
            WorkingTexture = basePipeline.getMain();
            glProg.useUtilProgram("pass_through");
            glProg.setTexture("InputBuffer", previousNode.WorkingTexture);
            glProg.drawBlocks(WorkingTexture);
            Log.d(Name, "Run() finished (pass-through)");
            return;
        }

        GLTexture input = previousNode.WorkingTexture;
        int w = input.mSize.x;
        int h = input.mSize.y;

        int pattern = cfaPatternOverride;
        if (pattern == -1) {
            // tofloat shifts every sensor CFA into RGGB coordinates.
            pattern = 0;
        }

        scratchTextures.clear();

        // Pass 1: per-photosite candidate ratios (full resolution).
        glProg.useAssetProgram("demosaic/rcd/highlight_recovery/highlight_recovery_ratio");
        glProg.setTexture("InputBuffer", input);
        glProg.setVar("clipThreshold", clipThreshold);
        glProg.setVar("chromaSampleMin", chromaSampleMin);
        glProg.setVar("cfaPattern", pattern);
        GLTexture level = getReduceScratch(w, h);
        glProg.drawBlocks(level);

        // Pass 2: halve repeatedly down to 1x1, summing each 2x2 block.
        int lw = w, lh = h;
        while (lw > 1 || lh > 1) {
            int nw = Math.max(1, (lw + 1) / 2);
            int nh = Math.max(1, (lh + 1) / 2);
            GLTexture next = getReduceScratch(nw, nh);

            glProg.useAssetProgram("demosaic/rcd/highlight_recovery/highlight_recovery_reduce");
            glProg.setTexture("SourceBuffer", level);
            glProg.setVar("sourceSize", lw, lh);
            glProg.drawBlocks(next);

            level = next;
            lw = nw;
            lh = nh;
        }
        GLTexture coeffBuffer = level; // now 1x1: (RsumNum,RsumW,BsumNum,BsumW)

        // Pass 3: reconstruct clipped photosites from local G + the reduced coefficients.
        glProg.useAssetProgram("demosaic/rcd/highlight_recovery/highlight_recovery_apply");
        glProg.setTexture("InputBuffer", input);
        glProg.setTexture("CoeffBuffer", coeffBuffer);
        glProg.setVar("clipThreshold", clipThreshold);
        glProg.setVar("cfaPattern", pattern);
        glProg.setVar("debugMask", debugMask);

        WorkingTexture = basePipeline.getMain();
        glProg.drawBlocks(WorkingTexture);

        // Cleanup intermediate textures
        for (GLTexture tex : scratchTextures) {
            if (tex != coeffBuffer) { // Keep the 1x1 buffer if needed (though not here)
                tex.close();
            }
        }
        coeffBuffer.close();
        scratchTextures.clear();
        
        glProg.close();
        Log.d(Name, "Run() finished");
    }

    private GLTexture getReduceScratch(int width, int height) {
        GLTexture tex = new GLTexture(width, height, new GLFormat(GLFormat.DataType.FLOAT_32, 4));
        scratchTextures.add(tex);
        return tex;
    }
}
