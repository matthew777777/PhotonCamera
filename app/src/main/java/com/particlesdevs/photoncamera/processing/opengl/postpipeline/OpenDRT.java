package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.GLImage;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;

import java.io.File;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * OpenDRT SDR display transform.
 *
 * Ported from Jed Smith's OpenDRT v1.1.0 (GPL-3.0-or-later):
 * https://github.com/jedypod/open-display-transform
 */
public class OpenDRT extends Node {
    private GLImage postLutImage;
    private GLTexture postLutTexture;
    @Tunable(title = "Display Peak (nits)", category = "OpenDRT", min = 80.0f, max = 400.0f,
            defaultValue = 100.0f, step = 1.0f, description = "SDR display peak used by the OpenDRT tone scale")
    float displayPeak = 100.0f;

    @Tunable(title = "Display Grey (nits)", category = "OpenDRT", min = 3.0f, max = 25.0f,
            defaultValue = 10.0f, step = 0.1f, description = "OpenDRT display grey luminance")
    float displayGrey = 10.0f;

    @Tunable(title = "Contrast", category = "OpenDRT", min = 1.0f, max = 2.0f,
            defaultValue = 1.66f, step = 0.01f, description = "OpenDRT Standard look tone contrast")
    float contrast = 1.66f;

    @Tunable(title = "Toe", category = "OpenDRT", min = 0.0f, max = 0.1f,
            defaultValue = 0.003f, step = 0.001f, description = "OpenDRT shadow toe")
    float toe = 0.003f;

    @Tunable(title = "HDR Grey Boost", category = "OpenDRT", min = 0.0f, max = 1.0f,
            defaultValue = 0.13f, step = 0.001f, description = "OpenDRT HDR grey boost; neutral at SDR peak")
    float greyBoost = 0.13f;

    @Tunable(title = "HDR Purity", category = "OpenDRT", min = 0.0f, max = 1.0f,
            defaultValue = 0.5f, step = 0.01f, description = "OpenDRT purity-compression blend from 100 to 1000 nit display peaks")
    float hdrPurity = 0.5f;

    @Tunable(title = "Look Preset", category = "OpenDRT", min = 0.0f, max = 6.0f,
            defaultValue = 0.0f, step = 1.0f,
            description = "0 Standard, 1 Arriba, 2 Sylvan, 3 Colorful, 4 Aery, 5 Dystopic, 6 Umbra")
    int lookPreset = 0;

    @Tunable(title = "Tone-scale Preset", category = "OpenDRT", min = 0.0f, max = 13.0f,
            defaultValue = 0.0f, step = 1.0f,
            description = "0 uses the look; 1 Low, 2 Medium, 3 High, 4-9 look scales, 10 ACES 1.x, 11 ACES 2.0, 12 Marvelous, 13 DaGrinchi")
    int toneScalePreset = 0;

    public OpenDRT() {
        super("", "OpenDRT");
    }

    @Override
    public void Compile() {
        // Program defines are supplied immediately before compiling in Run().
    }

    @Override
    public void AfterRun() {
        if (postLutTexture != null) {
            postLutTexture.close();
            postLutTexture = null;
        }
        if (postLutImage != null) {
            postLutImage.close();
            postLutImage = null;
        }
    }

    @Override
    public void Run() {
        if (((PostPipeline) basePipeline).toneMapper != 1) {
            WorkingTexture = previousNode.WorkingTexture;
            return;
        }
        glProg.setDefine("ODRT_PEAK", displayPeak);
        glProg.setDefine("ODRT_GREY", displayGrey);
        glProg.setDefine("ODRT_CONTRAST", contrast);
        glProg.setDefine("ODRT_TOE", toe);
        glProg.setDefine("ODRT_GREY_BOOST", greyBoost);
        glProg.setDefine("ODRT_HDR_PURITY", hdrPurity);
        glProg.setDefine("ODRT_LOOK", lookPreset);
        glProg.setDefine("ODRT_TONESCALE", toneScalePreset);
        glProg.setDefine("ODRT_OUTPUT_P3", ((PostPipeline) basePipeline).openDrtOutput == 1);
        File postLut = ((PostPipeline) basePipeline).openDrtPostLut;
        if (postLut != null && postLut.exists()) {
            postLutImage = new GLImage(postLut);
            int lutTiles = Math.round((float) Math.cbrt(postLutImage.size.x));
            if (postLutImage.size.x != postLutImage.size.y || lutTiles * lutTiles != postLutImage.size.x) {
                postLutImage.close();
                postLutImage = null;
                throw new IllegalArgumentException("OpenDRT post LUT must be a square N² by N² CLUT PNG");
            }
            postLutTexture = new GLTexture(postLutImage, GL_LINEAR, GL_CLAMP_TO_EDGE, 0);
            glProg.setDefine("ODRT_POSTLUT", true);
            glProg.setDefine("ODRT_POSTLUT_TILES", (float) lutTiles);
            glProg.setDefine("ODRT_POSTLUT_SIZE", (float) (lutTiles * lutTiles));
        }
        glProg.useAssetProgram("opendrt");
        glProg.setTexture("InputBuffer", previousNode.WorkingTexture);
        if (postLutTexture != null) {
            glProg.setTexture("PostLut", postLutTexture);
        }
        WorkingTexture = basePipeline.getMain();
        glProg.drawBlocks(WorkingTexture);
        glProg.closed = true;
    }
}
