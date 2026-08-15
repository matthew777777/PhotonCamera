package com.particlesdevs.photoncamera.debugclient;
import com.particlesdevs.photoncamera.app.PhotonCamera;

import android.hardware.camera2.CaptureRequest;

import com.particlesdevs.photoncamera.capture.CaptureController;

import java.io.PrintWriter;
import java.util.ArrayList;

import static com.particlesdevs.photoncamera.debugclient.DebugClient.getObjectString;

class PreviewRequestKeysPrint implements Command {
    private ArrayList<CaptureRequest.Key<?>> captureRequestKeys;
    private PrintWriter mBufferOut;

    public PreviewRequestKeysPrint(String[] str) {
        captureRequestKeys = new ArrayList<>(PhotonCamera.getCaptureController().getMPreviewCaptureRequest().getKeys());
    }

    @Override
    public void command() {
        StringBuilder keysStr = new StringBuilder();
        for (CaptureRequest.Key<?> key : captureRequestKeys) {
            keysStr.append(key.getName());
            keysStr.append("=");
            keysStr.append(getObjectString(PhotonCamera.getCaptureController().getMPreviewCaptureRequest().get(key)));
            keysStr.append(" ");
        }
        mBufferOut.println(keysStr.toString());
        return;
    }
}
