package com.particlesdevs.photoncamera.processing;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;

import com.particlesdevs.photoncamera.processing.processor.RawVideoProcessor;
import com.particlesdevs.photoncamera.util.Log;
import android.hardware.camera2.TotalCaptureResult;
import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.control.GyroBurst;
import com.particlesdevs.photoncamera.processing.processor.HdrxProcessor;
import com.particlesdevs.photoncamera.processing.processor.UnlimitedProcessor;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

public class DefaultSaver extends SaverImplementation {
    private static final String TAG = "DefaultSaver";
    final UnlimitedProcessor mUnlimitedProcessor;
    final RawVideoProcessor mRawVideoProcessor;
    final HdrxProcessor hdrxProcessor;

    public DefaultSaver(ProcessingEventsListener processingEventsListener) {
        super(processingEventsListener);
        this.hdrxProcessor = new HdrxProcessor(processingEventsListener);
        this.mUnlimitedProcessor = new UnlimitedProcessor(processingEventsListener);
        this.mRawVideoProcessor = new RawVideoProcessor(processingEventsListener);
    }

    public void runRaw(int imageFormat, CameraCharacteristics characteristics, CaptureResult captureResult, CaptureRequest captureRequest, ArrayList<GyroBurst> burstShakiness, int cameraRotation, HashMap<Long, Double> exposures, ArrayList<TotalCaptureResult> captureResults) {
        super.runRaw(imageFormat, characteristics, captureResult,captureRequest, burstShakiness, cameraRotation, exposures, captureResults);
        //Wait for one frame at least.
        Log.d(TAG, "Acquiring:" + IMAGE_BUFFER.size());
        while (bufferLock || IMAGE_BUFFER.isEmpty()){}
        Log.d(TAG, "Acquired:" + IMAGE_BUFFER.size());
        bufferLock = true;
        Log.d(TAG,"Size:"+IMAGE_BUFFER.size());
        
        if (PhotonCamera.getSettings().saveEachBracket && IMAGE_BUFFER.size() > 1) {
            String baseName = ImagePath.generateNewFileName("IMG");
            int toSave = Math.min(IMAGE_BUFFER.size(), captureResults.size());
            for (int i = 0; i < toSave; i++) {
                ImageFrame frame = IMAGE_BUFFER.get(i);
                TotalCaptureResult res = captureResults.get(i);
                
                // Calculate EV relative to base (0 EV is middle frame)
                int baseIndex = captureResults.size() / 2;
                int bracketingMode = PreferenceKeys.getBracketingMode();
                float evStep = (bracketingMode == 2) ? 2.0f : 1.0f;
                float ev = (i - baseIndex) * evStep;
                
                String evString = (ev >= 0 ? "+" : "") + String.format(Locale.US, "%.1f", ev) + "EV";
                Path bracketFile = ImagePath.newBracketDNGFilePath(baseName, i, evString);
                
                ImageSaver.Util.saveSingleRaw(bracketFile, frame, characteristics, res, cameraRotation);
                processingEventsListener.notifyImageSavedStatus(true, bracketFile);
            }
            processingEventsListener.onProcessingFinished("Saved Bracket Frames");
            for (int i = 0; i < toSave; i++) {
                IMAGE_BUFFER.get(i).close();
            }
            ArrayList<ImageFrame> remain = new ArrayList<>();
            for (int i = toSave; i < IMAGE_BUFFER.size(); i++) {
                remain.add(IMAGE_BUFFER.get(i));
            }
            IMAGE_BUFFER.clear();
            IMAGE_BUFFER.addAll(remain);
            bufferLock = false;
            processingCallback.onFinished();
            return;
        }

        Path dngFile = ImagePath.newDNGFilePath();
        Path imageFile = ImagePath.newImageFilePath();
        //Remove broken images
            /*for(int i =0; i<IMAGE_BUFFER.size();i++){
                try{
                    IMAGE_BUFFER.get(i).getFormat();
                } catch (IllegalStateException e){
                    IMAGE_BUFFER.remove(i);
                    i--;
                    Log.d(TAG,"IMGBufferSize:"+IMAGE_BUFFER.size());
                    e.printStackTrace();
                }
            }*/
        hdrxProcessor.configure(
                PhotonCamera.getSettings().alignAlgorithm,
                PhotonCamera.getSettings().rawSaver,
                PhotonCamera.getSettings().selectedMode
        );
        ArrayList<ImageFrame> slicedBuffer = new ArrayList<>();
        ArrayList<ImageFrame> imagebuffer = new ArrayList<>();
        for(int i =0; i<frameCount;i++){
            slicedBuffer.add(IMAGE_BUFFER.get(i));
        }
        for(int i = frameCount; i<IMAGE_BUFFER.size();i++){
            imagebuffer.add(IMAGE_BUFFER.get(i));
        }
        IMAGE_BUFFER.clear();
        IMAGE_BUFFER = imagebuffer;
        bufferLock = false;
        for(int i =0; i<slicedBuffer.size();i++){
            if (slicedBuffer.get(i) == null) {
                slicedBuffer.remove(i);
                i--;
                Log.d(TAG, "IMGBufferSize:" + slicedBuffer.size());
            }
        }

        Log.d(TAG,"moving images");
        //Log.d(TAG,"moved images:"+slicedBuffer.size());
        hdrxProcessor.start(
                dngFile,
                imageFile,
                ParseExif.parse(captureResult, captureRequest),
                burstShakiness,
                slicedBuffer,
                exposures,
                imageFormat,
                cameraRotation,
                characteristics,
                captureResult,
                captureRequest,
                processingCallback
        );
        slicedBuffer.clear();
    }

    public void processStart(int imageFormat, CameraCharacteristics characteristics, CaptureResult captureResult, CaptureRequest captureRequest, int cameraRotation) {
        super.processStart(imageFormat, characteristics, captureResult, captureRequest, cameraRotation);
        Path dngFile = ImagePath.newDNGFilePath();
        Path jpgFile = ImagePath.newImageFilePath();
        switch (PhotonCamera.getSettings().selectedMode) {
            case UNLIMITED:
                mUnlimitedProcessor.configure(PhotonCamera.getSettings().rawSaver);
                mUnlimitedProcessor.unlimitedStart(
                        dngFile,
                        jpgFile,
                        ParseExif.parse(captureResult, captureRequest),
                        characteristics,
                        captureResult,
                        captureRequest,
                        cameraRotation,
                        processingCallback
                );
                break;
            case RAWVIDEO:
                mRawVideoProcessor.videoStart(
                        ImagePath.getNewVideoFolderPath(),
                        ParseExif.parse(captureResult, captureRequest),
                        characteristics,
                        captureResult,
                        captureRequest,
                        cameraRotation,
                        processingCallback
                );
                break;
        }
    }

    public void processEnd() {
        switch (PhotonCamera.getSettings().selectedMode){
            case UNLIMITED:
                mUnlimitedProcessor.unlimitedEnd();
                break;
            case RAWVIDEO:
                mRawVideoProcessor.videoEnd();
                break;
        }
    }
}
