package com.particlesdevs.photoncamera.processing.live;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;

/**
 * Fragment node of the streamed preview pipeline. Consumes the color-corrected,
 * gamma-encoded output of StreamedColor and applies the user tone controls -
 * the same stage of the chain where the capture pipeline's Initial applies its
 * saturation and contrast operators. Renders into the pipeline's persistent
 * output texture instead of allocating a new one every frame.
 */
public class StreamedInitial extends Node {
    public StreamedInitial() {
        super("preview/streamed_post_pipeline", "StreamedInitial");
    }

    @Override
    public void Run() {
        StreamedPostPipeline pipeline = (StreamedPostPipeline) basePipeline;
        glProg.setTexture("InputBuffer", previousNode.WorkingTexture);
        glProg.setVar("saturation", (float) basePipeline.mSettings.saturation);
        glProg.setVar("contrast", (float) basePipeline.mSettings.contrastMpy);
        glProg.setVar("shadows", (float) basePipeline.mSettings.shadows);
        glProg.setVar("compressor", (float) basePipeline.mSettings.compressor);
        WorkingTexture = pipeline.outputTexture;
    }
}
