package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;
import com.particlesdevs.photoncamera.util.Log;

public class ModernToneMapping extends Node {
    private static final String TAG = "ModernToneMapping";

    @Tunable(title = "Enable Tone Mapping", category = "Modern Tone", subTab = "Experimental Pipeline", defaultValue = 1, min = 0, max = 1, step = 1)
    boolean enable = true;

    @Tunable(title = "Tonemapping Method", category = "Modern Tone", subTab = "Experimental Pipeline", defaultValue = 0, min = 0, max = 1, step = 1, description = "0: ACES, 1: OpenDRT")
    int tonemapMethod = 0;

    @Tunable(title = "Tone Mapping Strength", category = "Modern Tone", subTab = "Experimental Pipeline", min = 0.1f, max = 5.0f, defaultValue = 1.0f)
    float strength = 1.0f;

    @Tunable(title = "Gamma", category = "Modern Tone", subTab = "Experimental Pipeline", min = 1.0f, max = 3.0f, defaultValue = 2.2f)
    float gamma = 2.2f;

    @Tunable(title = "ACES A", category = "Modern Tone", subTab = "Experimental Pipeline", min = 0.0f, max = 5.0f, defaultValue = 2.51f)
    float acesA = 2.51f;

    @Tunable(title = "ACES B", category = "Modern Tone", subTab = "Experimental Pipeline", min = 0.0f, max = 1.0f, defaultValue = 0.03f)
    float acesB = 0.03f;

    @Tunable(title = "ACES C", category = "Modern Tone", subTab = "Experimental Pipeline", min = 0.0f, max = 5.0f, defaultValue = 2.43f)
    float acesC = 2.43f;

    @Tunable(title = "ACES D", category = "Modern Tone", subTab = "Experimental Pipeline", min = 0.0f, max = 2.0f, defaultValue = 0.59f)
    float acesD = 0.59f;

    @Tunable(title = "ACES E", category = "Modern Tone", subTab = "Experimental Pipeline", min = 0.0f, max = 1.0f, defaultValue = 0.14f)
    float acesE = 0.14f;

    @Tunable(title = "OpenDRT Contrast", category = "Modern Tone", subTab = "Experimental Pipeline", min = 0.5f, max = 2.0f, defaultValue = 1.15f)
    float odtContrast = 1.15f;

    @Tunable(title = "OpenDRT Display Grey", category = "Modern Tone", subTab = "Experimental Pipeline", min = 0.01f, max = 0.50f, defaultValue = 0.18f)
    float odtLg = 0.18f;

    @Tunable(title = "OpenDRT Toe", category = "Modern Tone", subTab = "Experimental Pipeline", min = 0.0f, max = 0.1f, defaultValue = 0.02f)
    float odtToe = 0.02f;

    @Tunable(title = "OpenDRT Purity", category = "Modern Tone", subTab = "Experimental Pipeline", min = 0.0f, max = 1.0f, defaultValue = 0.5f)
    float odtPurity = 0.5f;

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

        // Calculate sx (shoulder scale) to satisfy the middle grey intersection constraint.
        // f(x) = (x / (x + sx))^p
        // The toe changes the input before the sigmoid, so solve against the
        // toe-transformed middle grey rather than the raw 0.18 value.
        float grey = 0.18f;
        float toeGrey = grey * grey / (grey + odtToe + 1e-6f);
        float sx = toeGrey * ((float) Math.pow(odtLg, -1.0f / odtContrast) - 1.0f);

        Log.d(TAG, "Run() - method: " + tonemapMethod + ", strength: " + strength + ", sx: " + sx);

        glProg.useAssetProgram("modern_tonemapping");
        glProg.setTexture("InputBuffer", previousNode.WorkingTexture);
        glProg.setVar("tonemapMethod", tonemapMethod);
        glProg.setVar("strength", strength);
        glProg.setVar("gamma", gamma);
        glProg.setVar("acesA", acesA);
        glProg.setVar("acesB", acesB);
        glProg.setVar("acesC", acesC);
        glProg.setVar("acesD", acesD);
        glProg.setVar("acesE", acesE);
        glProg.setVar("odtContrast", odtContrast);
        glProg.setVar("odtSx", sx);
        glProg.setVar("odtToe", odtToe);
        glProg.setVar("odtPurity", odtPurity);

        WorkingTexture = basePipeline.getMain();
        glProg.drawBlocks(WorkingTexture);
        glProg.closed = true;
    }
}
