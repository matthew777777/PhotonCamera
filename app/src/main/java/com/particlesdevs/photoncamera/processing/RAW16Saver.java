package com.particlesdevs.photoncamera.processing;

import android.media.Image;
import android.os.AsyncTask;
import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.api.CameraMode;
import com.particlesdevs.photoncamera.app.PhotonCamera;

public class RAW16Saver extends DefaultSaver{
    private static final String TAG = "RAW16Saver";
    public RAW16Saver(ProcessingEventsListener processingEventsListener) {
        super(processingEventsListener);
    }

    public void addImage(Image image) {
        try {
            switch (PhotonCamera.getSettings().selectedMode) {
                case RAWVIDEO:
                    Log.d(TAG, "rawvideoaddImage: " + this + " " + mRawVideoProcessor);
                    mRawVideoProcessor.videoCycle(image);
                    // image.close() is handled inside videoCycle
                    bufferSemaphore.release();
                    break;
                case UNLIMITED:
                    Log.d(TAG, "unlimitedaddImage: " + this + " " + mUnlimitedProcessor);
                    mUnlimitedProcessor.unlimitedCycle(image);
                    image.close();
                    bufferSemaphore.release();
                    break;
                default:
                    Log.d(TAG, "start buffer size:" + IMAGE_BUFFER.size());
                    image.getFormat();
                    IMAGE_BUFFER.add(getFrame(image));
                    image.close();
                    bufferSemaphore.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in addImage: " + e.getMessage());
            try {
                image.close();
            } catch (Exception ignored) {}
            bufferSemaphore.release();
        }
    }
}
