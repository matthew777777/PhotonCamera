package com.particlesdevs.photoncamera.processing.processor;

import android.graphics.Bitmap;
import android.graphics.Point;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;

import com.particlesdevs.photoncamera.processing.opengl.scripts.PyramidMerging;
import com.particlesdevs.photoncamera.util.Log;
import androidx.exifinterface.media.ExifInterface;
import com.particlesdevs.photoncamera.api.Camera2ApiAutoFix;
import com.particlesdevs.photoncamera.api.CameraMode;
import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.control.GyroBurst;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.ImageFrameDeblur;
import com.particlesdevs.photoncamera.processing.ImageSaver;
import com.particlesdevs.photoncamera.processing.ProcessingEventsListener;
import com.particlesdevs.photoncamera.processing.ProcessingLog;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.PostPipeline;
import com.particlesdevs.photoncamera.processing.parameters.ExposureIndex;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.processing.parameters.FrameNumberSelector;
import com.particlesdevs.photoncamera.processing.parameters.IsoExpoSelector;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.util.Allocator;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;

public class HdrxProcessor extends ProcessorBase {
    private static final String TAG = "HdrxProcessor";
    private ArrayList<ImageFrame> mImageFramesToProcess;
    private HashMap<Long, Double> exposures;
    private int imageFormat;
    /* config */
    private int alignAlgorithm;
    private int saveRAW;
    private CameraMode cameraMode;
    private ArrayList<GyroBurst> BurstShakiness;


    public HdrxProcessor(ProcessingEventsListener processingEventsListener) {
        super(processingEventsListener);
    }

    public void configure(int alignAlgorithm, int saveRAW, CameraMode cameraMode) {
        this.alignAlgorithm = alignAlgorithm;
        this.saveRAW = saveRAW;
        this.cameraMode = cameraMode;
    }

    public void start(Path dngFile, Path imageFile,
                      ParseExif.ExifData exifData,
                      ArrayList<GyroBurst> BurstShakiness,
                      ArrayList<ImageFrame> imageBuffer,
                      HashMap<Long, Double> exposures,
                      int imageFormat,
                      int cameraRotation,
                      CameraCharacteristics characteristics,
                      CaptureResult captureResult,
                      CaptureRequest captureRequest,
                      ProcessingCallback callback) {
        this.imageFile = imageFile;
        this.dngFile = dngFile;
        this.exifData = exifData;
        if (BurstShakiness != null) {
            this.BurstShakiness = new ArrayList<>(BurstShakiness);
        } else {
            this.BurstShakiness = new ArrayList<>();
        }
        this.imageFormat = imageFormat;
        this.cameraRotation = cameraRotation;
        this.mImageFramesToProcess = imageBuffer;
        this.exposures = exposures;
        this.callback = callback;
        this.characteristics = characteristics;
        this.captureResult = captureResult;
        this.captureRequest = captureRequest;
        Log.d(TAG, "HdrxProcessor called start()");
        Run();
    }

    public void Run() {
        try {
            Camera2ApiAutoFix.ApplyRes(captureResult);
            if (imageFormat == CaptureController.RAW_FORMAT) {
                ApplyHdrX();
            } else {
                Log.d(TAG, "HdrX processing skipped due to unsupported image format: " + imageFormat);
                callback.onFinished();
                return;
            }
//            if (isYuv) {
//                ApplyStabilization();
//            }
        } catch (Exception e) {
            Log.e(TAG, ProcessingEventsListener.FAILED_MSG);
            Log.e(TAG, "Error in HdrX Processing:"+Log.getStackTraceString(e));
            callback.onFailed();
            processingEventsListener.onProcessingError("HdrX Processing Failed");
        }
    }

    private void ApplyHdrX() {
        callback.onStarted();
        processingEventsListener.onProcessingStarted("HDRX");

        ProcessingLog processingLog = new ProcessingLog();
        long totalStartTime = System.currentTimeMillis();

        Log.d(TAG, "ApplyHdrX() called from" + Thread.currentThread().getName());

        long startTime = System.currentTimeMillis();
        Log.d(TAG, "ApplyHdrX() mImageFramesToProcess.size():" + mImageFramesToProcess.size());
        
        try {
            if (mImageFramesToProcess.isEmpty()) {
                Log.e(TAG, "ApplyHdrX: mImageFramesToProcess is empty");
                callback.onFailed();
                processingEventsListener.onProcessingError("No images to process");
                return;
            }
            if (exposures == null) {
                Log.e(TAG, "ApplyHdrX: exposures map is null");
                callback.onFailed();
                processingEventsListener.onProcessingError("Exposure data missing");
                return;
            }
            int width = mImageFramesToProcess.get(0).width;
            int height = mImageFramesToProcess.get(0).height;
            if (width <= 0 || height <= 0) {
                Log.e(TAG, "ApplyHdrX: Invalid image dimensions: " + width + "x" + height);
                callback.onFailed();
                processingEventsListener.onProcessingError("Invalid image dimensions");
                return;
            }
            Log.d(TAG, "APPLY HDRX: buffer:" + mImageFramesToProcess.get(0).buffer.asShortBuffer().remaining());
            Log.d(TAG, "Api WhiteLevel:" + characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL));
            Log.d(TAG, "Api BlackLevel:" + characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN));
            Parameters processingParameters = new Parameters();
            processingParameters.FillConstParameters(characteristics, new Point(width, height));
            // sort by timestamp first
            mImageFramesToProcess.sort(Comparator.comparingLong(ImageFrame::getTimestamp));

            double minExpo = Double.MAX_VALUE;
            for (ImageFrame frame : mImageFramesToProcess) {
                Double expo = exposures.get(frame.getTimestamp());
                if (expo != null) {
                    minExpo = Math.min(minExpo, expo);
                }
            }
            if (minExpo == Double.MAX_VALUE) {
                minExpo = 1.0;
            }
            Log.d(TAG, "Wrapper.init");
            ArrayList<ImageFrame> images = new ArrayList<>();
            int ISO = 0;
            int normalFrames = 0;
            if (BurstShakiness.isEmpty()) {
                Log.w(TAG, "ApplyHdrX: BurstShakiness is empty, adding default");
                BurstShakiness.add(new GyroBurst(1));
            }
            
            processingLog.totalFrames = mImageFramesToProcess.size();
            processingLog.startTime = totalStartTime;
            for (int i = 0; i < mImageFramesToProcess.size(); i++) {
                ImageFrame frame = mImageFramesToProcess.get(i);
                frame.frameGyro = BurstShakiness.get(i % BurstShakiness.size()); // cyclic for safety

                if (i >= IsoExpoSelector.fullpairs.size()) {
                    Log.e(TAG, "ApplyHdrX: No fullpair for frame at index " + i);
                    continue;
                }
                frame.pair = new IsoExpoSelector.ExpoPair(IsoExpoSelector.fullpairs.get(i));

                Double expo = exposures.get(frame.getTimestamp());
                if (expo == null) {
                    Log.e(TAG, "ApplyHdrX: Missing exposure for frame " + i + " timestamp " + frame.getTimestamp());
                    continue;
                }

                frame.number = i;
                frame.pair.layerMpy = (float) (expo / minExpo);
                int ev = (int) Math.round(Math.log(frame.pair.layerMpy) / Math.log(2.0));
                processingLog.frameInfos.add(new ProcessingLog.FrameInfo(i, ev, (int) frame.pair.iso,
                        ExposureIndex.sec2string(ExposureIndex.time2sec(frame.pair.exposure)),
                        "Accepted", 1.0f / (1.0f + frame.frameGyro.shakiness)));
                if (frame.pair.layerMpy > 1.0) {
                    frame.pair.curlayer = IsoExpoSelector.ExpoPair.exposureLayer.High;
                } else {
                    frame.pair.curlayer = IsoExpoSelector.ExpoPair.exposureLayer.Normal;
                    normalFrames++;
                }
                Log.d(TAG, "Mpy:" + frame.pair.layerMpy);
                images.add(frame);
                ISO += frame.pair.iso;
            }
            if (images.isEmpty()) {
                Log.e(TAG, "ApplyHdrX: No valid images to process");
                callback.onFailed();
                processingEventsListener.onProcessingError("No valid images to process");
                return;
            }
            ISO /= images.size();

            processingParameters.FillDynamicParameters(captureResult, captureRequest, ISO);
            processingParameters.cameraRotation = cameraRotation;

            ParseExif.syncWithParameters(exifData, processingParameters);
            ImageFrameDeblur imageFrameDeblur = new ImageFrameDeblur(processingParameters);
            imageFrameDeblur.firstFrameGyro = images.get(0).frameGyro.clone();
            for (int i = 0; i < images.size(); i++)
                imageFrameDeblur.processDeblurPosition(images.get(i));
            if (images.size() >= 3)
                images.sort((img1, img2) -> Float.compare(img1.frameGyro.shakiness, img2.frameGyro.shakiness));
            double unluckypickiness = 1.05;
            float unluckyavr = 0;
            for (ImageFrame image : images) {
                unluckyavr += image.frameGyro.shakiness;
                Log.d(TAG, "unlucky map:" + image.frameGyro.shakiness + "n:" + image.number);
            }
            unluckyavr /= images.size();
            // search for high exposure close frame by time
            int highind = -1;
            int timeDiff = Integer.MAX_VALUE;
            for (int i = 0; i < images.size(); i++) {
                if (images.get(i).pair.curlayer == IsoExpoSelector.ExpoPair.exposureLayer.High) {
                    int diff = (int) Math.abs(images.get(i).timestamp - images.get(0).timestamp);
                    if (diff < timeDiff) {
                        timeDiff = diff;
                        highind = i;
                    }
                }
            }
            // swap to second
            if (highind != -1) {
                ImageFrame frame = images.get(0);
                images.set(0, images.get(highind));
                images.set(highind, frame);
            }

            if (images.size() > 10) {
                int size = (int) (images.size() - FrameNumberSelector.throwCount);
                if (size >= images.size())
                    size = (int) (images.size() * 0.75);
                for (int i = images.size(); i > size; i--) {
                    ImageFrame cur = images.get(images.size() - 1);
                    float curunlucky = cur.frameGyro.shakiness;
                    if (curunlucky > unluckyavr * unluckypickiness) {
                        if (normalFrames == 1 && cur.pair.curlayer == IsoExpoSelector.ExpoPair.exposureLayer.Normal) {
                            continue;
                        }
                        if (cur.pair.curlayer == IsoExpoSelector.ExpoPair.exposureLayer.Normal) {
                            normalFrames--;
                        }
                        Log.d(TAG, "Removing unlucky:" + curunlucky + " number:" + images.get(images.size() - 1).number);
                        for (ProcessingLog.FrameInfo info : processingLog.frameInfos) {
                            if (info.index == cur.number) {
                                info.status = "REJECTED";
                                info.reason = "Unlucky";
                                break;
                            }
                        }
                        images.get(images.size() - 1).close();
                        images.remove(images.size() - 1);
                    }
                }
                Log.d(TAG, "Size after removal:" + images.size());
            }

            float minMpy = 1000.f;
            for (int i = 0; i < images.size(); i++) {
                if (images.get(i).pair.layerMpy < minMpy) {
                    minMpy = images.get(i).pair.layerMpy;
                }
            }
            
            int selected = 0;
            for (int i = 0; i < images.size(); i++) {
                if (images.get(i).pair.layerMpy == minMpy) {
                    selected = i;
                    break;
                }
            }

            // move selected image to 0 index
            if (selected != 0) {
                ImageFrame frame = images.get(0);
                images.set(0, images.get(selected));
                images.set(selected, frame);
            }

            Log.d(TAG, "White Level:" + processingParameters.whiteLevel);
            Log.d(TAG, "Wrapper.loadFrame");

            ByteBuffer output = null;
            Log.d(TAG, "Packing");
            Log.d(TAG, "Packed");
            if (images.size() > 1) {
                long mergeStart = System.currentTimeMillis();
                PyramidMerging pyramidMerging = new PyramidMerging(new Point(width, height), images);
                pyramidMerging.parameters = processingParameters;
                pyramidMerging.cameraMode = cameraMode;
                try {
                    pyramidMerging.Run();
                    processingLog.mergeTimeMs = System.currentTimeMillis() - mergeStart;
                    processingLog.mergedFrames = images.size();
                    processingLog.discardedFrames = processingLog.totalFrames - images.size();
                    output = pyramidMerging.Output;
                } finally {
                    pyramidMerging.close();
                    for (int i = 0; i < images.size(); i++) {
                        images.get(i).close();
                    }
                }
                IncreaseWLBL(processingParameters);
            } else {
                output = images.get(0).buffer;
                images.get(0).buffer = null;
                images.get(0).close();
            }
            Log.d(TAG, "HDRX Alignment elapsed:" + (System.currentTimeMillis() - startTime) + " ms");
            if ((saveRAW >= 1) && alignAlgorithm != 2) {
                boolean imageSaved = ImageSaver.Util.saveStackedRaw(dngFile, output,
                        processingParameters);
                processingEventsListener.notifyImageSavedStatus(imageSaved, dngFile);
                if (saveRAW == 2) {
                    processingEventsListener.onProcessingFinished("HdrX RAW Processing Finished");
                    callback.onFinished();
                    Allocator.free(output);
                    Allocator.getMemoryCount();
                    return;
                }
            }

            processingParameters.noiseModeler.computeStackingNoiseModel(images.size());

            PostPipeline pipeline = new PostPipeline();
            try {
                long jpgStart = System.currentTimeMillis();
                Bitmap img = pipeline.Run(output, processingParameters, processingLog);
                if (img == null) {
                    Log.e(TAG, "ApplyHdrX: PostPipeline returned null bitmap");
                    Allocator.free(output);
                    callback.onFailed();
                    processingEventsListener.onProcessingError("Pipeline processing failed");
                    return;
                }
                processingLog.jpgTimeMs = System.currentTimeMillis() - jpgStart;
                processingLog.totalTimeMs = System.currentTimeMillis() - totalStartTime;

                Allocator.free(output);

                img = overlay(img, pipeline.debugData.toArray(new Bitmap[0]));
                PhotonCamera.setLatestProcessingLog(processingLog);
                try {
                    processingEventsListener.onProcessingFinished(processingLog);
                } catch (Exception e) {
                    Log.d(TAG, "Error in processingEventsListener.onProcessingFinished:" + Log.getStackTraceString(e));
                }
                imageFile = Paths.get(imageFile.toAbsolutePath() + ".jpg");

                boolean isExperimental = PreferenceKeys.isExperimentalJpegPipelineOn();
                int chromaSubsampling = PreferenceKeys.getJpegChromaSubsampling();
                int jpgQuality = isExperimental ? PhotonCamera.getSettings().experimentalJpegQuality : ImageSaver.JPG_QUALITY;
                boolean use444 = isExperimental && (chromaSubsampling == 1);

                if (isExperimental) {
                    Log.d(TAG, "ExperimentalJPEG:");
                    Log.d(TAG, "quality = " + jpgQuality);
                    Log.d(TAG, "chromaSubsampling = " + (use444 ? "4:4:4" : "4:2:0"));
                    Log.d(TAG, "encoder = " + (use444 ? "stb_image_write" : "Android Bitmap.compress"));
                    processingLog.jpgSettings.put("pipeline", "Experimental");
                    processingLog.jpgSettings.put("quality", String.valueOf(jpgQuality));
                    processingLog.jpgSettings.put("subsampling", use444 ? "4:4:4" : "4:2:0");
                } else {
                    processingLog.jpgSettings.put("pipeline", "Legacy");
                }

                //Saves the final bitmap
                exifData.ORIENTATION = String.valueOf(ExifInterface.ORIENTATION_NORMAL);
                boolean imageSaved = ImageSaver.Util.saveBitmapAsJPG(imageFile, img,
                        jpgQuality, exifData, use444);

                try {
                    processingEventsListener.notifyImageSavedStatus(imageSaved, imageFile);
                } catch (Exception e) {
                    Log.d(TAG, "Error in processingEventsListener.notifyImageSavedStatus:" + Log.getStackTraceString(e));
                }
            } finally {
                pipeline.close();
            }

            Allocator.getMemoryCount();
            callback.onFinished();
        } catch (Exception e) {
            Log.e(TAG, "Exception in ApplyHdrX: " + Log.getStackTraceString(e));
            // Ensure all frames are closed on exception
            for (ImageFrame frame : mImageFramesToProcess) {
                if (frame != null) frame.close();
            }
            callback.onFailed();
        }
    }

}
