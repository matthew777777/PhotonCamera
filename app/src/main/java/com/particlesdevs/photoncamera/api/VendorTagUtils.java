package com.particlesdevs.photoncamera.api;

import android.annotation.SuppressLint;
import android.hardware.camera2.CaptureRequest;

import com.particlesdevs.photoncamera.util.Log;

public class VendorTagUtils {
    private static final String TAG = "VendorTagUtils";
    private static boolean isSupported(CaptureRequest.Builder builder,
                                       CaptureRequest.Key<?> key) {
        boolean supported = true;
        try {
            builder.get(key);
        }catch(IllegalArgumentException exception){
            supported = false;
            Log.w(TAG,"vendor tag " + key.getName() + " is not supported");
        }
        if ( supported ) {
            Log.d(TAG,"vendor tag " + key.getName() + " is supported");
        }
        return supported;
    }

    @SuppressLint("NewApi")
    private static <T> void setPhysical(CaptureRequest.Builder builder, CaptureRequest.Key<T> key, T value, String physicalId) {
        try {
            builder.setPhysicalCameraKey(key, value, physicalId);
        } catch (Exception e) {
            Log.w(TAG, "Error setting physical camera key: " + key.getName() + " for camera: " + physicalId, e);
        }
    }
    @SuppressLint({"NewApi", "LocalSuppress"})
    public static void builderSessionApply(CaptureRequest.Builder builder, boolean burst, boolean useMaximumResolutionKey, String physicalId) {
        try {
            byte enable = 1;
             var clientName = new CaptureRequest.Key<>("com.xiaomi.sessionparams.clientName", String.class);
            if(isSupported(builder,clientName)) {
                Log.d(TAG, "com.xiaomi.sessionparams.clientName supported");
                builder.set(clientName, "com.android.camera");
                setPhysical(builder, clientName, "com.android.camera", physicalId);
            }
            if(burst) {
                var remosaicEnabled = new CaptureRequest.Key<>("xiaomi.remosaic.enabled", Byte.class);
                if (isSupported(builder, remosaicEnabled)) {
                    builder.set(remosaicEnabled, enable);
                    setPhysical(builder, remosaicEnabled, enable, physicalId);
                }
                var remosaicQuadEnabled = new CaptureRequest.Key<>("xiaomi.quadcfa.enabled", Byte.class);
                if (isSupported(builder, remosaicQuadEnabled)) {
                    builder.set(remosaicQuadEnabled, enable);
                    setPhysical(builder, remosaicQuadEnabled, enable, physicalId);
                }
                var remosaicEnabled2 = new CaptureRequest.Key<>("com.mediatek.control.capture.remosaicenable", int[].class);
                if (isSupported(builder, remosaicEnabled2)) {
                    builder.set(remosaicEnabled2, new int[]{1});
                    setPhysical(builder, remosaicEnabled2, new int[]{1}, physicalId);
                }
            }
            /*var mode = new CaptureRequest.Key<>("org.codeaurora.qcamera3.sensor_meta_data.current_mode", Integer.class);
            if (isSupported(builder, mode)) {
                builder.set(mode, 0);
            }
            var mode_index = new CaptureRequest.Key<>("com.qti.sensorbps.mode_index", Integer.class);
            if (isSupported(builder, mode_index)) {
                builder.set(mode_index, 0);
            }*/
            var aeMeteringMode = new CaptureRequest.Key<>("com.mediatek.3afeature.aeMeteringMode", Byte.class);
            int meteringVal = com.particlesdevs.photoncamera.settings.PreferenceKeys.getAeMetering();
            if (meteringVal != -1 && isSupported(builder, aeMeteringMode)) {
                Log.d(TAG, "aeMeteringMode is supported, setting to " + meteringVal);
                builder.set(aeMeteringMode, (byte) meteringVal);
            }
        } catch (Exception e){
            Log.w(TAG, "Error applying vendor tags to CaptureRequest.Builder", e);
        }
        if(useMaximumResolutionKey) {
            builder.set(CaptureRequest.SENSOR_PIXEL_MODE, CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
        }
    }
}
