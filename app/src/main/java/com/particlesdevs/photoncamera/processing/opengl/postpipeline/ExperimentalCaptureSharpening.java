package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.util.Log;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;

public class ExperimentalCaptureSharpening extends Node {
    public ExperimentalCaptureSharpening() {
        super("", "ExpCaptureSharpening");
    }

    @Override
    public void Compile() {}

    @Tunable(
        title = "Strength", 
        category = "Capture Sharpening", 
        min = 0.0f, 
        max = 5.0f, 
        defaultValue = 1.5f,
        description = "Sharpening intensity"
    )
    float strength = 1.5f;

    @Tunable(
        title = "Threshold", 
        category = "Capture Sharpening", 
        min = 0.0f, 
        max = 0.1f, 
        defaultValue = 0.01f, 
        step = 0.001f,
        description = "Contrast threshold to avoid sharpening flat areas"
    )
    float threshold = 0.01f;

    @Tunable(
        title = "Noise Floor", 
        category = "Capture Sharpening", 
        min = 0.0f, 
        max = 0.05f, 
        defaultValue = 0.005f, 
        step = 0.001f,
        description = "Prevents sharpening of sensor noise"
    )
    float noiseFloor = 0.005f;

    @Tunable(
        title = "Debug Response", 
        category = "Capture Sharpening", 
        min = 0, 
        max = 1, 
        defaultValue = 0, 
        step = 1,
        description = "Visualize the sharpening mask"
    )
    int debugResponse = 0;

    @Tunable(
        title = "Pass Through",
        category = "Capture Sharpening",
        min = 0,
        max = 1,
        defaultValue = 1,
        step = 1,
        description = "Disable algorithm and pass input directly to output"
    )
    int passThrough = 1;

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
            glProg.useAssetProgram("demosaic/rcd/capture_sharpening");
            glProg.setVar("strength", strength);
            glProg.setVar("threshold", threshold);
            glProg.setVar("noiseFloor", noiseFloor);
            glProg.setVar("debugResponse", debugResponse);
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
