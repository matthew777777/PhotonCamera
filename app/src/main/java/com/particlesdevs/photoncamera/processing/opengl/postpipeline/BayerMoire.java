package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;

public class BayerMoire extends Node {


    public BayerMoire() {
        super("", "BayerMoire");
    }

    @Override
    public void Compile() {}

    int tile = 8;
    @Override
    public void Run() {
        glProg.setLayout(tile,tile,1);
        glProg.setDefine("OUTSET",previousNode.WorkingTexture.mSize);
        glProg.setDefine("TILE",tile);
        glProg.setDefine("NOISEO",basePipeline.noiseO);
        glProg.setDefine("NOISES",basePipeline.noiseS);
        int msize = 5;
        glProg.setDefine("MSIZE", msize);
        glProg.setDefine("KERNELSIZE", 5.5f);
        glProg.useAssetProgram("BayerMoire/bayermoire",true);
        glProg.setTextureCompute("inTexture",previousNode.WorkingTexture,false);
        WorkingTexture = basePipeline.getMain();
        glProg.setTextureCompute("outTexture",WorkingTexture,true);
        //for(int i =0; i<5;i++)
        glProg.computeAuto(WorkingTexture.mSize,1);
        glProg.closed = true;
    }
}
