package com.particlesdevs.photoncamera.processing.parameters;

import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.capture.CaptureController;

import java.util.Locale;

public class ExposureIndex {
    public static final long sec = 1000000000;

    public static double index() {
        return index(PhotonCamera.getCaptureController());
    }

    public static double index(CaptureController controller) {
        if (controller == null) return 0;
        long exposureTime = controller.mPreviewExposureTime;
        int iso = controller.mPreviewIso;
        double time = (double) (exposureTime) / sec;
        return iso * time;
    }

    public static double time2sec(long in) {
        return ((double) in) / sec;
    }

    public static String sec2string(double in) {
        if (in > 1.0) return String.format(Locale.US, "%.2f", in);
        else {
            in = 1.0 / in;
            return "1/" + String.format(Locale.US, "%.0f", in);
        }
    }

    public static long sec2time(double in) {
        return (long) (in * sec);
    }
}
