package com.particlesdevs.photoncamera.api;

import android.annotation.SuppressLint;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
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
    @SuppressLint({"NewApi", "LocalSuppress"})
    public static void builderSessionApply(CaptureRequest.Builder builder, boolean burst, boolean useMaximumResolutionKey) {
        try {
            byte enable = 1;
             var clientName = new CaptureRequest.Key<>("com.xiaomi.sessionparams.clientName", String.class);
            if(isSupported(builder,clientName)) {
                Log.d(TAG, "com.xiaomi.sessionparams.clientName supported");
                builder.set(clientName, "com.android.camera");
            }
            if(burst) {
                var remosaicEnabled = new CaptureRequest.Key<>("xiaomi.remosaic.enabled", Byte.class);
                if (isSupported(builder, remosaicEnabled)) {
                    Log.d(TAG, "remosaic.enabled is supported");
                    builder.set(remosaicEnabled, enable);
                }
                var remosaicQuadEnabled = new CaptureRequest.Key<>("xiaomi.quadcfa.enabled", Byte.class);
                if (isSupported(builder, remosaicQuadEnabled)) {
                    Log.d(TAG, "quadcfa.enabled is supported");
                    builder.set(remosaicQuadEnabled, enable);
                }
                var remosaicEnabled2 = new CaptureRequest.Key<>("com.mediatek.control.capture.remosaicenable", int[].class);
                if (isSupported(builder, remosaicEnabled2)) {
                    Log.d(TAG, "capture.remosaicenable is supported");
                    builder.set(remosaicEnabled2, new int[]{1});
                }
            }
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
