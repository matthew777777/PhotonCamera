package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;

public class BayerConcat extends Node {
    boolean concat;

    public BayerConcat(boolean concat) {
        super("", "BayerConcat");
        this.concat = concat;
    }

    @Override
    public void Compile() {}

    int tile = 8;
    @Override
    public void Run() {
        glProg.setLayout(tile,tile,1);
        glProg.setDefine("TILE",tile);
        glProg.setDefine("CONCAT", concat);
        glProg.useAssetProgram("Concat/concat",true);
        glProg.setTextureCompute("inTexture",previousNode.WorkingTexture,false);
        WorkingTexture = basePipeline.getMain();
        glProg.setTextureCompute("outTexture",WorkingTexture,true);
        glProg.computeAuto(WorkingTexture.mSize,1);
        glProg.closed = true;
    }
}
