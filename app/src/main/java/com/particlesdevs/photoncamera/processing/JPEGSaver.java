package com.particlesdevs.photoncamera.processing;

import android.media.Image;

import com.particlesdevs.photoncamera.app.PhotonCamera;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

public class JPEGSaver extends DefaultSaver {
    private static final String TAG = "JPEGSaver";
    public JPEGSaver(ProcessingEventsListener processingEventsListener) {
        super(processingEventsListener);
    }

    public void addImage(Image image) {
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            IMAGE_BUFFER.add(getFrame(image));
            byte[] bytes = new byte[buffer.remaining()];
            if (IMAGE_BUFFER.size() == PhotonCamera.getCaptureController().mMeasuredFrameCnt && PhotonCamera.getSettings().frameCount != 1) {
                Path jpgPath = ImagePath.newImageFilePath();
                buffer.duplicate().get(bytes);
                Files.write(jpgPath, bytes);
                IMAGE_BUFFER.clear();
            }
            if (PhotonCamera.getSettings().frameCount == 1) {
                Path jpgPath = ImagePath.newImageFilePath();
                IMAGE_BUFFER.clear();
                buffer.get(bytes);
                Files.write(jpgPath, bytes);
                processingEventsListener.onProcessingFinished("JPEG: Single Frame, Not Processed!");
                processingEventsListener.notifyImageSavedStatus(true, jpgPath);
            }
        } catch (IOException | NullPointerException e) {
            e.printStackTrace();
        } finally {
            image.close();
            bufferSemaphore.release();
        }
    }
}
