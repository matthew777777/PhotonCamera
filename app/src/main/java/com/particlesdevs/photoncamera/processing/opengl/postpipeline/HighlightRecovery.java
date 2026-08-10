package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.util.Log;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;

public class HighlightRecovery extends Node {
    public HighlightRecovery() {
        super("", "HighlightRecovery");
    }

    @Override
    public void Compile() {}

    @Tunable(
        title = "Clip Threshold", 
        category = "Highlight Recovery", 
        min = 0.90f, 
        max = 1.0f, 
        defaultValue = 0.98f,
        description = "Threshold above which pixels are considered clipped"
    )
    float clipThreshold = 0.98f;

    @Tunable(
        title = "Debug Mask", 
        category = "Highlight Recovery", 
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
        min = 0,
        max = 1,
        defaultValue = 0,
        step = 1,
        description = "Disable algorithm and pass input directly to output"
    )
    int passThrough = 0;

    @Override
    public void Run() {
        Log.d(Name, "Run() started, passThrough=" + passThrough);
        if (previousNode.WorkingTexture == null) {
            Log.e(Name, "Input texture is NULL!");
            return;
        }
        
        if (passThrough == 1) {
            glProg.useUtilProgram("pass_through");
        } else {
            glProg.useAssetProgram("demosaic/rcd/highlight_recovery");
            glProg.setVar("clipThreshold", clipThreshold);
            glProg.setVar("debugMask", debugMask);
        }
        glProg.setTexture("InputBuffer", previousNode.WorkingTexture);
        
        WorkingTexture = basePipeline.getMain();
        if (WorkingTexture == null) {
            Log.e(Name, "Output texture (basePipeline.getMain()) is NULL!");
            return;
        }
        
        Log.d(Name, "Dispatching: " + WorkingTexture.mSize.x + "x" + WorkingTexture.mSize.y);
        glProg.drawBlocks(WorkingTexture);
        glProg.closed = true;
        Log.d(Name, "Run() finished");
    }
}
