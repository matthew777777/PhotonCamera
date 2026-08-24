package com.particlesdevs.photoncamera.processing;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Gainmap;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.os.Build;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.media.Image;
import android.media.ImageReader;
import com.particlesdevs.photoncamera.util.Log;

import androidx.exifinterface.media.ExifInterface;

import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.control.GyroBurst;
import com.particlesdevs.photoncamera.processing.render.Parameters;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;

import com.particlesdevs.photoncamera.settings.TunableInjector;

import static com.particlesdevs.photoncamera.processing.ImageSaverSelector.getImageSaver;
import static com.particlesdevs.photoncamera.processing.ImageSaverSelector.init;

public class ImageSaver {
    /**
     * Image frame buffer
     */
    public static final int JPG_QUALITY = 98;
    private static final String TAG = "ImageSaver";

    public static final ImageSaverSettings SETTINGS = new ImageSaverSettings();

    public SaverImplementation implementation;
    private int imageFormat;
    private int frameCounter = 0;
    private int desiredFrameCount = 0;
    public boolean newBurst = false;

    public void setFrameCount(int desiredFrameCount){
        this.desiredFrameCount = desiredFrameCount;
    }

    public void setImageFormat(int imageFormat) {
        this.imageFormat = imageFormat;
    }

    public void updateFrameCount(int desiredFrameCount){
        this.desiredFrameCount = desiredFrameCount;
        this.implementation.frameCount = desiredFrameCount;
    }

    public int bufferSize(){
        return SaverImplementation.IMAGE_BUFFER.size();
    }

    public ImageSaver(ProcessingEventsListener processingEventsListener) {
        implementation = new DefaultSaver(processingEventsListener);
        init(implementation);
        TunableInjector.inject(SETTINGS);
    }

    public void initProcess(ImageReader mReader) {
        Log.v(TAG, "initProcess()");
        if((frameCounter < desiredFrameCount) || desiredFrameCount == -1) {
            Log.v(TAG, "initProcess() : called from \"" + Thread.currentThread().getName() + "\" Thread");
            Image mImage;
            try {
                mImage = mReader.acquireNextImage();
            } catch (Exception ignored) {
                return;
            }
            if (mImage == null)
                return;
            int format = mImage.getFormat();
            imageFormat = mReader.getImageFormat();
            implementation = getImageSaver(format, implementation);
            Log.d(TAG,"Implementation:" + implementation);
            implementation.frameCount = desiredFrameCount;
            implementation.newBurst = newBurst;
            implementation.addImage(mImage);
        } else {
            Image mImage;
            try {
                mImage = mReader.acquireNextImage();
            } catch (Exception ignored) {
                return;
            }
            if (mImage == null)
                return;
            mImage.close();
        }
        frameCounter++;
    }

    public void runRaw(CameraCharacteristics characteristics, CaptureResult captureResult, CaptureRequest captureRequest, ArrayList<GyroBurst> burstShakiness, int cameraRotation, HashMap<Long, Double> exposures) {
        TunableInjector.inject(SETTINGS);
        implementation.runRaw(imageFormat,characteristics,captureResult, captureRequest,burstShakiness,cameraRotation, exposures);
    }

    public void processStart(CameraCharacteristics characteristics, CaptureResult captureResult, CaptureRequest captureRequest, int cameraRotation) {
        TunableInjector.inject(SETTINGS);
        implementation = ImageSaverSelector.getImageSaver(ImageFormat.RAW_SENSOR, implementation);
        implementation.processStart(imageFormat,characteristics,captureResult, captureRequest,cameraRotation);
    }

    public void processEnd() {
        implementation.processEnd();
    }

    public static class Util {
        public static boolean saveBitmapAsJPG(Path fileToSave, Bitmap img, int jpgQuality, ParseExif.ExifData exifData) {
            exifData.COMPRESSION = String.valueOf(jpgQuality);
            // Bitmap's JPEG encoder writes the embedded ICC profile.  EXIF's
            // ColorSpace tag has no Display-P3 value, so mark non-sRGB files
            // as uncalibrated and let the ICC profile remain authoritative.
            exifData.COLOR_SPACE = img.getColorSpace().isSrgb() ? "1" : "65535";
            try {
                OutputStream outputStream = Files.newOutputStream(fileToSave);
                Bitmap jpeg = img;
                if (SETTINGS.ultraHdr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    jpeg = UltraHdrApi34.withHighlightGainMap(img);
                }
                boolean compressed = jpeg.compress(Bitmap.CompressFormat.JPEG, jpgQuality, outputStream);
                outputStream.flush();
                outputStream.close();
                if (jpeg != img) jpeg.recycle();
                img.recycle();
                if (!compressed) return false;
                ExifInterface inter = ParseExif.setAllAttributes(fileToSave.toFile(), exifData);
                inter.saveAttributes();
                return true;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }

        /** Android 14's gain-map APIs are isolated so older devices never resolve them. */
        private static final class UltraHdrApi34 {
            private static final float MAX_HDR_RATIO = 4.0f;

            private static Bitmap withHighlightGainMap(Bitmap source) {
                Bitmap base = source.isMutable() ? source : source.copy(source.getConfig(), true);
                if (base == null) return source;
                int width = Math.max(1, (base.getWidth() + 3) / 4);
                int height = Math.max(1, (base.getHeight() + 3) / 4);
                Bitmap contents = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8);
                ByteBuffer bytes = ByteBuffer.allocate(width * height);
                for (int y = 0; y < height; y++) {
                    int sourceY = Math.min(base.getHeight() - 1, y * 4 + 2);
                    for (int x = 0; x < width; x++) {
                        int pixel = base.getPixel(Math.min(base.getWidth() - 1, x * 4 + 2), sourceY);
                        float luminance = (0.2126f * Color.red(pixel) + 0.7152f * Color.green(pixel)
                                + 0.0722f * Color.blue(pixel)) / 255.0f;
                        float highlight = smoothstep(0.65f, 1.0f, luminance);
                        bytes.put((byte) Math.round(highlight * 255.0f));
                    }
                }
                bytes.rewind();
                contents.copyPixelsFromBuffer(bytes);
                Gainmap gainmap = new Gainmap(contents);
                gainmap.setRatioMin(1.0f, 1.0f, 1.0f);
                gainmap.setRatioMax(MAX_HDR_RATIO, MAX_HDR_RATIO, MAX_HDR_RATIO);
                gainmap.setGamma(1.0f, 1.0f, 1.0f);
                gainmap.setMinDisplayRatioForHdrTransition(1.0f);
                gainmap.setDisplayRatioForFullHdr(MAX_HDR_RATIO);
                base.setGainmap(gainmap);
                return base;
            }

            private static float smoothstep(float edge0, float edge1, float value) {
                float t = Math.max(0.0f, Math.min(1.0f, (value - edge0) / (edge1 - edge0)));
                return t * t * (3.0f - 2.0f * t);
            }
        }

        /*public static boolean saveBitmapAsAVIF(Path fileToSave, Bitmap img, int jpgQuality, ParseExif.ExifData exifData) {
            exifData.COMPRESSION = String.valueOf(jpgQuality);
            try {
                OutputStream outputStream = Files.newOutputStream(fileToSave);
                //img.compress(Bitmap.CompressFormat.JPEG, jpgQuality, outputStream);
                HeifCoder coder = new HeifCoder();
                var buffer = coder.encodeAvif(img, jpgQuality, PreciseMode.LOSSY, AvifSpeed.EIGHT);
                outputStream.write(buffer);
                outputStream.flush();
                outputStream.close();
                img.recycle();
                //ExifInterface inter = ParseExif.setAllAttributes(fileToSave.toFile(), exifData);
                //inter.saveAttributes();
                return true;
            } catch (IOException e) {
                //e.printStackTrace();
                Log.d(TAG,"AVIF save error:"+Log.getStackTraceString(e));
                return false;
            }
        }*/

        public static boolean saveBitmapAsPNG(Path fileToSave, Bitmap img, int pngQuality, ParseExif.ExifData exifData) {
            try {
                OutputStream outputStream = Files.newOutputStream(fileToSave);
                img.compress(Bitmap.CompressFormat.PNG, pngQuality, outputStream);
                outputStream.flush();
                outputStream.close();
                img.recycle();
                ExifInterface inter = ParseExif.setAllAttributes(fileToSave.toFile(), exifData);
                inter.saveAttributes();
                return true;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }

        public static boolean saveStackedRaw(Path dngFilePath,
                                             ByteBuffer buffer, Parameters parameters) {
            return saveSingleRaw(dngFilePath, buffer, parameters);
        }
        public static boolean saveSingleRaw(Path dngFilePath,
                                            ImageFrame image,
                                            CameraCharacteristics characteristics,
                                            CaptureResult captureResult,
                                            int cameraRotation) {
            Parameters parameters = new Parameters();

            parameters.FillConstParameters(characteristics, new Point(image.width, image.height));
            int iso = captureResult.get(CaptureResult.SENSOR_SENSITIVITY);
            parameters.FillDynamicParameters(captureResult, null, iso);
            parameters.cameraRotation = cameraRotation;
            Log.d(TAG, "Camera rotation: " + parameters.cameraRotation);
            Log.d(TAG, "activearr:" + characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE));
            Log.d(TAG, "precorr:" + characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE));
            return saveSingleRaw(dngFilePath, image.buffer, parameters);
        }

        public static boolean saveSingleRaw(Path dngFilePath,
                                            ByteBuffer buffer, Parameters parameters) {
            DngCreator dngCreator = new DngCreator();
            dngCreator.setParameters(parameters);
            dngCreator.setCompression(false);
            //dngCreator.setBinning(true);
            try {
                OutputStream outputStream = Files.newOutputStream(dngFilePath);
                dngCreator.writeBuffer(outputStream, buffer, parameters.rawSize.x, parameters.rawSize.y);
                outputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
            return true;
        }
    }
}
