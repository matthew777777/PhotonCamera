package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;

public class Concat extends Node {

    boolean concat;
    public Concat(boolean concat) {
        super("", "Concat");
        this.concat = concat;
    }

    @Override
    public void Compile() {}

    int tile = 8;
    @Override
    public void Run() {
        glProg.setLayout(tile,tile,1);
        glProg.setDefine("TILE",tile);
        if (concat)
            glProg.setDefine("CONCAT", 1);
        else
            glProg.setDefine("CONCAT", 0);
        glProg.useAssetProgram("Concat/concat",true);
        glProg.setTextureCompute("inTexture",previousNode.WorkingTexture,false);
        WorkingTexture = basePipeline.getMain();
        glProg.setTextureCompute("outTexture",WorkingTexture,true);
        glProg.computeManual(WorkingTexture.mSize.x/(tile*2),WorkingTexture.mSize.y/(tile*2),1);
        glProg.closed = true;
    }
}
