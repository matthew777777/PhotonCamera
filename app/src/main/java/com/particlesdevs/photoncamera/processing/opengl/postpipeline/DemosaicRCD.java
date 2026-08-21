package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import java.util.Locale;
import com.particlesdevs.photoncamera.util.Log;
import com.particlesdevs.photoncamera.processing.opengl.GLBuffer;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLCoreBlockProcessing;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;

public class DemosaicRCD extends Node {
    public DemosaicRCD() {
        super("", "DemosaicRCD");
    }

    @Override
    public void Compile() {}

    @Tunable(
        title = "Debug Mode", 
        category = "DemosaicRCD", 
        min = 0, 
        max = 18, 
        defaultValue = 0, 
        step = 1,
        description = "0:Normal, 1:Dir, 2:Edge, 3:Red, 4:Blue, 5:R/G, 6:B/G, 9:Conf, 10:R/G Map, 11:B/G Map, 12:Edge Rej, 13:Ratio Rej, 14:Chroma Corr, 15:Heatmap, 16:Massive Corr, 17:Same-Side Mask, 18:RelJump Detector"
    )
    int debugMode = 0;

    @Tunable(
        title = "Artifact Correction",
        category = "DemosaicRCD",
        defaultValue = 1,
        min = 0,
        max = 1,
        step = 1,
        description = "Enable advanced RCD artifact correction logic"
    )
    boolean rcdArtifactCorrection = true;

    @Tunable(
        title = "Green Equilibration",
        category = "DemosaicRCD",
        min = 0.0f,
        max = 1.0f,
        defaultValue = 0.0f,
        description = "Balance G1 and G2 channels to fix grid/maze artifacts"
    )
    float rcdGreenEquilibration = 0.0f;

    @Tunable(
        title = "Ratio Smoothing",
        category = "DemosaicRCD",
        min = 0,
        max = 1,
        defaultValue = 0,
        step = 1,
        description = "Apply median filter to color ratios to suppress chromatic noise"
    )
    boolean rcdRatioSmoothing = false;

    @Tunable(title = "Edge Sensitivity", category = "DemosaicRCD", min = 0.0f, max = 2.0f, defaultValue = 1.0f)
    float rcdEdgeSensitivity = 1.0f;

    @Tunable(title = "Direction Confidence", category = "DemosaicRCD", min = 0.0f, max = 2.0f, defaultValue = 0.5f)
    float rcdDirectionConfidence = 0.5f;

    @Tunable(title = "Ratio Robustness", category = "DemosaicRCD", min = 0.0f, max = 2.0f, defaultValue = 1.0f)
    float rcdRatioRobustness = 1.0f;

    @Tunable(title = "Ratio Edge Protection", category = "DemosaicRCD", min = 0.0f, max = 2.0f, defaultValue = 1.0f)
    float rcdRatioEdgeProtection = 1.0f;

    @Tunable(title = "Chroma Correction Strength", category = "DemosaicRCD", min = 0.0f, max = 2.0f, defaultValue = 0.5f)
    float rcdChromaCorrectionStrength = 0.5f;

    @Tunable(
        title = "Epsilon",
        category = "DemosaicRCD",
        min = 0.0001f,
        max = 0.01f,
        defaultValue = 0.001f,
        step = 0.0001f,
        description = "Small value to avoid division by zero in ratio calculations"
    )
    float epsilon = 0.001f;

    @Tunable(
        title = "Pass Through",
        category = "DemosaicRCD",
        min = 0,
        max = 1,
        defaultValue = 0,
        step = 1,
        description = "Disable algorithm and use simple Bayer-to-RGB copy"
    )
    int passThrough = 0;

    @Tunable(
        title = "Debug Stage",
        category = "DemosaicRCD",
        min = 1,
        max = 10,
        defaultValue = 6,
        step = 1,
        description = "1: Copy, 2: Green Verified, 3: Bilinear R/B, 5: Ratio, 6: Full, 7: Test A (Red), 8: Test B (UV), 9: Test C (Bayer), 10: CFA Diagnostic"
    )
    int debugStage = 6;

    private GLBuffer statsBuffer;

    @Override
    public void Run() {
        Log.d(Name, "Run() started, passThrough=" + passThrough + ", debugStage=" + debugStage + " ArtifactCorrection=" + rcdArtifactCorrection);
        GLTexture inTex = previousNode.WorkingTexture;
        if (inTex == null) {
            Log.e(Name, "Input texture is NULL!");
            return;
        }
        
        if (rcdArtifactCorrection && statsBuffer == null) {
            statsBuffer = new GLBuffer(8, new GLFormat(GLFormat.DataType.UNSIGNED_32));
        }

        int width = inTex.mSize.x;
        int height = inTex.mSize.y;
        
        // LOG INPUT TEXTURE DETAILS AND BAYER PATTERN
        Log.d(Name, "Input Texture: ID=" + inTex.mTextureID + 
              ", Size=" + width + "x" + height + 
              ", Format=" + inTex.mGLFormat + 
              ", Channels=" + inTex.mFormat.mChannels);
        Log.d(Name, "Bayer Pattern (CFA): " + basePipeline.mParameters.cfaPattern);

        if (passThrough == 1) {
            WorkingTexture = basePipeline.getMain();
            if (WorkingTexture == null) {
                Log.e(Name, "Output texture (basePipeline.getMain()) is NULL!");
                return;
            }
            Log.d(Name, "Output texture ID: " + WorkingTexture.mTextureID);
            glProg.useAssetProgram("demosaic/rcd/pass_through");
            glProg.setTexture("InputBuffer", inTex);
            Log.d(Name, "Dispatching pass-through fragment shader: " + width + "x" + height);
            glProg.drawBlocks(WorkingTexture);
        } else {
            int tile = 8;
            
            // PREPARE OUTPUT TEXTURE
            WorkingTexture = basePipeline.getMain();
            if (WorkingTexture == null) {
                Log.e(Name, "Output texture (basePipeline.getMain()) is NULL!");
                return;
            }
            Log.d(Name, "Output Texture: ID=" + WorkingTexture.mTextureID + 
                  ", Size=" + WorkingTexture.mSize.x + "x" + WorkingTexture.mSize.y + 
                  ", Format=" + WorkingTexture.mGLFormat);

            if (debugStage == 7 || debugStage == 1) { // Test A: Constant Red OR Stage 1 (Simplified Copy)
                glProg.setLayout(tile, tile, 1);
                if (debugStage == 1) {
                    Log.d(Name, "Running Stage 1: Simplified Copy (matching Test C logic)");
                    glProg.useAssetProgram("demosaic/rcd/test_c", true);
                    glProg.setTextureCompute("inTexture", inTex, false);
                } else {
                    Log.d(Name, "Running Test A: Constant Red");
                    glProg.useAssetProgram("demosaic/rcd/test_a", true);
                }
                glProg.setTextureCompute("outTexture", WorkingTexture, true);
                glProg.computeManual((width + tile - 1) / tile, (height + tile - 1) / tile, 1);
            } else if (debugStage == 8) { // Test B: UV Coordinates
                glProg.setLayout(tile, tile, 1);
                Log.d(Name, "Running Test B: UV Coordinates");
                glProg.useAssetProgram("demosaic/rcd/test_b", true);
                glProg.setTextureCompute("outTexture", WorkingTexture, true);
                glProg.computeManual((width + tile - 1) / tile, (height + tile - 1) / tile, 1);
            } else if (debugStage == 9) { // Test C: Raw Bayer
                glProg.setLayout(tile, tile, 1);
                Log.d(Name, "Running Test C: Raw Bayer");
                glProg.useAssetProgram("demosaic/rcd/test_c", true);
                glProg.setTextureCompute("inTexture", inTex, false);
                glProg.setTextureCompute("outTexture", WorkingTexture, true);
                glProg.computeManual((width + tile - 1) / tile, (height + tile - 1) / tile, 1);
            } else if (debugStage == 10) { // Test D: CFA Diagnostic
                glProg.setLayout(tile, tile, 1);
                Log.d(Name, "Running Test D: CFA Diagnostic");
                glProg.setDefine("DEBUGSTAGE", 10);
                glProg.useAssetProgram("demosaic/rcd/full_pass", true);
                glProg.setTextureCompute("inTexture", inTex, false);
                glProg.setTextureCompute("outTexture", WorkingTexture, true);
                glProg.setVar("CfaPattern", basePipeline.mParameters.cfaPattern);
                glProg.setVar("imgSize", width, height);
                glProg.computeManual((width + tile - 1) / tile, (height + tile - 1) / tile, 1);
            } else {
                // RUN STAGED RCD
                Log.d(Name, "Running Staged RCD, Stage=" + debugStage + " ArtifactCorrection=" + rcdArtifactCorrection);
                
                // Pass 1: Green Reconstruction & Selection
                GLTexture greenData = basePipeline.main3;
                if (greenData == null) {
                    Log.e(Name, "Intermediate texture (basePipeline.main3) is NULL!");
                    return;
                }
                
                if (debugStage >= 2) {
                    Log.d(Name, "Pass 1: Green Reconstruction dispatching " + width + "x" + height);
                    glProg.setLayout(tile, tile, 1);
                    glProg.setDefine("DEBUGSTAGE", debugStage);
                    glProg.setDefine("ARTIFACT_CORRECTION", rcdArtifactCorrection ? 1 : 0);
                    glProg.useAssetProgram("demosaic/rcd/green_pass", true);
                    glProg.setTextureCompute("inTexture", inTex, false);
                    glProg.setTextureCompute("outTexture", greenData, true);
                    glProg.setVar("imgSize", width, height);
                    glProg.setVar("CfaPattern", basePipeline.mParameters.cfaPattern);
                    glProg.setVar("edgeSensitivity", rcdEdgeSensitivity);
                    glProg.setVar("dirConfidence", rcdDirectionConfidence);
                    glProg.setVar("greenEquil", rcdGreenEquilibration);
                    glProg.computeManual((width + tile - 1) / tile, (height + tile - 1) / tile, 1);
                    GLCoreBlockProcessing.checkEglError(Name + " Pass 1");
                }

                // Pass 2: Final Reconstruction
                Log.d(Name, "Pass 2: Final Reconstruction dispatching " + width + "x" + height);
                glProg.setLayout(tile, tile, 1);
                glProg.setDefine("DEBUGSTAGE", debugStage);
                glProg.setDefine("EPS", epsilon);
                glProg.setDefine("ARTIFACT_CORRECTION", rcdArtifactCorrection ? 1 : 0);
                glProg.setDefine("RATIO_SMOOTHING", rcdRatioSmoothing ? 1 : 0);
                glProg.useAssetProgram("demosaic/rcd/full_pass", true);
                if (rcdArtifactCorrection) {
                    glProg.setBufferCompute("relJumpStats", statsBuffer);
                }
                glProg.setTextureCompute("inTexture", inTex, false);
                glProg.setTextureCompute("greenTexture", greenData, false);
                glProg.setTextureCompute("outTexture", WorkingTexture, true);
                glProg.setVar("debugMode", debugMode);
                glProg.setVar("imgSize", width, height);
                glProg.setVar("CfaPattern", basePipeline.mParameters.cfaPattern);
                glProg.setVar("ratioRobustness", rcdRatioRobustness);
                glProg.setVar("ratioEdgeProtection", rcdRatioEdgeProtection);
                glProg.setVar("chromaCorrStr", rcdChromaCorrectionStrength);
                glProg.computeManual((width + tile - 1) / tile, (height + tile - 1) / tile, 1);
                GLCoreBlockProcessing.checkEglError(Name + " Pass 2");
                
                if (rcdArtifactCorrection && statsBuffer != null) {
                    int[] stats = statsBuffer.readBufferIntegers(true);
                    if (stats != null && stats.length >= 8) {
                        float total = (float)stats[7];
                        if (total > 0.0f) {
                            Log.d(Name, String.format(Locale.US, "Experiment C Stats: total=%.0f, <0.05=%.1f%%, 0.05-0.1=%.1f%%, 0.1-0.2=%.1f%%, 0.2-0.5=%.1f%%, >0.5=%.1f%%",
                                    total, (stats[0] / total) * 100.0f, (stats[1] / total) * 100.0f, (stats[2] / total) * 100.0f, (stats[3] / total) * 100.0f, (stats[4] / total) * 100.0f));
                            Log.d(Name, String.format(Locale.US, "Experiment C Jump Thresholds: >0.2=%.1f%%, >0.5=%.1f%%",
                                    (stats[5] / total) * 100.0f, (stats[6] / total) * 100.0f));
                        }
                    }
                }
            }
        }
        
        if (statsBuffer != null) {
            statsBuffer.close();
            statsBuffer = null;
        }

        glProg.close();
        Log.d(Name, "Run() finished");
    }
}
