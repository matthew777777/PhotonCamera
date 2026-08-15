package com.particlesdevs.photoncamera.debugclient;
import com.particlesdevs.photoncamera.app.PhotonCamera;

import android.hardware.camera2.CaptureResult;

import com.particlesdevs.photoncamera.capture.CaptureController;

import java.io.PrintWriter;
import java.util.List;

import static com.particlesdevs.photoncamera.debugclient.DebugClient.previewKeyValue;

class PreviewKeysPrint implements Command {
    private PrintWriter mBufferOut;
    private List<CaptureResult.Key<?>> resultKeys;

    public PreviewKeysPrint(String[] str) {
        resultKeys = PhotonCamera.getCaptureController().getMPreviewCaptureResult().getKeys();
    }

    @Override
    public void command() {
        StringBuilder keysStr = new StringBuilder();
        for (CaptureResult.Key<?> key : resultKeys) {
            keysStr.append(key.getName());
            keysStr.append("=");
            keysStr.append(previewKeyValue(key));
            keysStr.append(" ");
        }
        mBufferOut.println(keysStr.toString());
        return;
    }
}
