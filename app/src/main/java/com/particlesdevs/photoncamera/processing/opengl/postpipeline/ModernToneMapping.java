package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;
import com.particlesdevs.photoncamera.util.Log;

public class ModernToneMapping extends Node {
    private static final String TAG = "ModernToneMapping";

    @Tunable(title = "Enable Tone Mapping", category = "Modern Tone", defaultValue = 1, min = 0, max = 1, step = 1)
    boolean enable = true;

    @Tunable(title = "Tone Mapping Strength", category = "Modern Tone", min = 0.1f, max = 2.0f, defaultValue = 1.0f)
    float strength = 1.0f;

    @Tunable(title = "Gamma", category = "Modern Tone", min = 1.0f, max = 3.0f, defaultValue = 2.2f)
    float gamma = 2.2f;

    public ModernToneMapping() {
        super("", "ModernToneMapping");
    }

    @Override
    public void Compile() {
    }

    @Override
    public void Run() {
        if (!enable) {
            WorkingTexture = previousNode.WorkingTexture;
            return;
        }

        Log.d(TAG, "Run() - strength: " + strength + ", gamma: " + gamma);

        glProg.useAssetProgram("modern_tonemapping");
        glProg.setTexture("InputBuffer", previousNode.WorkingTexture);
        glProg.setVar("strength", strength);
        glProg.setVar("gamma", gamma);

        WorkingTexture = basePipeline.getMain();
        glProg.drawBlocks(WorkingTexture);
        glProg.closed = true;
    }
}
