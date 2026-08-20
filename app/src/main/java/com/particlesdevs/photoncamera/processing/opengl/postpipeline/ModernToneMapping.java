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

    @Tunable(title = "ACES A", category = "Modern Tone", min = 0.0f, max = 5.0f, defaultValue = 2.51f)
    float acesA = 2.51f;

    @Tunable(title = "ACES B", category = "Modern Tone", min = 0.0f, max = 1.0f, defaultValue = 0.03f)
    float acesB = 0.03f;

    @Tunable(title = "ACES C", category = "Modern Tone", min = 0.0f, max = 5.0f, defaultValue = 2.43f)
    float acesC = 2.43f;

    @Tunable(title = "ACES D", category = "Modern Tone", min = 0.0f, max = 2.0f, defaultValue = 0.59f)
    float acesD = 0.59f;

    @Tunable(title = "ACES E", category = "Modern Tone", min = 0.0f, max = 1.0f, defaultValue = 0.14f)
    float acesE = 0.14f;

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
        glProg.setVar("acesA", acesA);
        glProg.setVar("acesB", acesB);
        glProg.setVar("acesC", acesC);
        glProg.setVar("acesD", acesD);
        glProg.setVar("acesE", acesE);

        WorkingTexture = basePipeline.getMain();
        glProg.drawBlocks(WorkingTexture);
        glProg.closed = true;
    }
}
