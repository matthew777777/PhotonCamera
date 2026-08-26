package com.particlesdevs.photoncamera.processing.live;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;

/** One-pass streamed render: LSC, matrices, histogram GTM, and display controls. */
public final class StreamedRender extends Node {
    public StreamedRender() {
        super("preview/streamed_render", "StreamedRender");
    }

    @Override
    public void Run() {
        StreamedPostPipeline pipeline = (StreamedPostPipeline) basePipeline;
        pipeline.configureColorProgram(glProg, basePipeline.mParameters);
        glProg.setTexture("InputBuffer", pipeline.inputTexture);
        glProg.setTexture("ToneCurve", pipeline.currentToneCurve());
        glProg.setVar("saturation", (float) basePipeline.mSettings.saturation);
        glProg.setVar("contrast", (float) basePipeline.mSettings.contrastMpy);
        glProg.setVar("shadows", (float) basePipeline.mSettings.shadows);
        WorkingTexture = pipeline.outputTexture;
    }
}
