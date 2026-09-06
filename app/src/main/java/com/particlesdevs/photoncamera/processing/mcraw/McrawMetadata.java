package com.particlesdevs.photoncamera.processing.mcraw;

import android.hardware.camera2.CaptureResult;
import android.os.Build;
import android.util.Rational;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import com.particlesdevs.photoncamera.processing.render.Parameters;

public final class McrawMetadata {
    private static final Gson GSON = new Gson();
    private McrawMetadata() {}
    public static String container(Parameters p, double fps, int audioSampleRate, int audioChannels) {
        JsonObject o = new JsonObject();
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String model = Build.MODEL == null ? "" : Build.MODEL;
        String uniqueCameraModel = (manufacturer + " " + model).trim();
        if (uniqueCameraModel.isEmpty()) uniqueCameraModel = "PhotonCamera";
        o.addProperty("manufacturer", manufacturer);
        o.addProperty("model", model);
        // DNG tag 50708 name. Decoders should use this instead of hardcoding
        // "MotionCam" when exporting frames from a compatible container.
        o.addProperty("UniqueCameraModel", uniqueCameraModel);
        o.addProperty("uniqueCameraModel", uniqueCameraModel);
        o.addProperty("sensorArrangment", new String[]{"rggb","grbg","gbrg","bggr"}[p.cfaPattern]);
        o.add("blackLevel", GSON.toJsonTree(p.blackLevel));
        o.addProperty("whiteLevel", p.whiteLevel);
        o.add("colorMatrix1", GSON.toJsonTree(p.ColorMatrix1));
        o.add("colorMatrix2", GSON.toJsonTree(p.ColorMatrix2));
        o.add("forwardMatrix1", GSON.toJsonTree(p.ForwardTransform1));
        o.add("forwardMatrix2", GSON.toJsonTree(p.ForwardTransform2));
        o.add("calibrationMatrix1", GSON.toJsonTree(p.calibrationTransform1));
        o.add("calibrationMatrix2", GSON.toJsonTree(p.calibrationTransform2));
        o.addProperty("referenceIlluminant1", p.calibrationIlluminant1);
        o.addProperty("referenceIlluminant2", p.calibrationIlluminant2);
        JsonObject extra = new JsonObject();
        extra.addProperty("formatName", "MediaCinemaRAW");
        extra.addProperty("encoder", "PhotonCamera clean-room implementation");
        extra.addProperty("frameRate", fps);
        extra.addProperty("recordingType", "VIDEO");
        extra.addProperty("useAccurateTimestamp", true);
        extra.addProperty("audioSampleRate", audioSampleRate);
        extra.addProperty("audioChannels", audioChannels);
        extra.addProperty("mediaLayout", "embedded");
        o.add("extraData",extra);
        return o.toString();
    }
    public static String frame(Parameters p, CaptureResult result, int w, int h, long timestamp,
                               long receivedTimestampMs) {
        JsonObject o = new JsonObject();
        o.addProperty("width", w); o.addProperty("height", h);
        o.addProperty("originalWidth",w); o.addProperty("originalHeight",h);
        o.addProperty("rowStride",w*2);
        o.addProperty("compressionType",7); o.addProperty("isCompressed",true);
        o.addProperty("pixelFormat","raw16");
        o.addProperty("timestamp",Long.toString(timestamp));
        o.addProperty("filename",Long.toString(timestamp));
        o.addProperty("recvdTimestampMs",Long.toString(receivedTimestampMs));
        o.addProperty("type","ZSL");
        o.addProperty("screenOrientation",p.cameraRotation);
        Long metadataTimestamp = result == null ? null : result.get(CaptureResult.SENSOR_TIMESTAMP);
        o.addProperty("metadataTimestamp",metadataTimestamp == null ? "" : Long.toString(metadataTimestamp));
        o.addProperty("metadataMatched",metadataTimestamp != null && metadataTimestamp == timestamp);
        Long exposure = result == null ? null : result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = result == null ? null : result.get(CaptureResult.SENSOR_SENSITIVITY);
        Rational[] neutral = result == null ? null : result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
        o.addProperty("exposureTime",exposure == null ? (long)(p.exposureTime*1e9) : exposure);
        o.addProperty("iso",iso == null ? p.iso : iso);
        float[] wb = p.whitePoint;
        if (neutral != null && neutral.length == 3) {
            wb = new float[]{neutral[0].floatValue(),neutral[1].floatValue(),neutral[2].floatValue()};
        }
        o.add("asShotNeutral",GSON.toJsonTree(wb));
        return o.toString();
    }
}
