package com.particlesdevs.photoncamera.processing;

import android.media.Image;
import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.app.PhotonCamera;

public class RAW16Saver extends DefaultSaver{
    private static final String TAG = "RAW16Saver";
    public RAW16Saver(ProcessingEventsListener processingEventsListener) {
        super(processingEventsListener);
    }

    public void addImage(Image image) {
        switch (PhotonCamera.getSettings().selectedMode) {
            case RAWVIDEO:
                mRawVideoProcessor.videoCycle(image);
                //image.close();
                bufferLock = false;
                break;
            case UNLIMITED:
                Log.d(TAG, "unlimitedaddImage: " + this + " " + mUnlimitedProcessor);
                mUnlimitedProcessor.unlimitedCycle(image);
                image.close();
                bufferLock = false;
                break;
            default:
                Log.d(TAG, "start buffer size:" + IMAGE_BUFFER.size());
                image.getFormat();
                /*while (bufferLock){
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException ignored) {}
                }*/
                IMAGE_BUFFER.add(getFrame(image));
                image.close();
                bufferLock = false;
        }
    }
}
