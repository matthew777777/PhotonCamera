package com.particlesdevs.photoncamera.processing.parameters;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import com.particlesdevs.photoncamera.util.Log;
import android.util.Range;

import com.particlesdevs.photoncamera.api.CameraMode;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import java.util.ArrayList;

public class IsoExpoSelector {
    public static final int baseFrame = 1;
    private static final String TAG = "IsoExpoSelector";
    public static boolean HDR = false;
    public static boolean useTripod = false;
    public static final int patternSize = 3;
    public static ArrayList<ExpoPair> pairs = new ArrayList<>();
    public static ArrayList<ExpoPair> fullpairs = new ArrayList<>();
    public static long lastSelectedExposure = 0;

    // ---- Shutter-Priority / Dynamic Low-Light AE Curve (HDR+ Enhanced style) ----
    // Instead of letting stock 3A pick a fast shutter + high ISO, we keep the SAME
    // total exposure the platform metered (exposure_time * iso is still a valid
    // brightness target) and re-split it: push shutter time up first - more real
    // photons land on the sensor per frame, which is a genuine shot-noise SNR win
    // even at identical brightness - and only fall back to ISO once a per-frame
    // time cap is hit. That cap is not one fixed number: it slides between a
    // "start extending here" value and a darker-scene "ceiling" as metered scene
    // darkness increases (see ExpoPair#applyShutterPriorityCurve), so behavior
    // changes smoothly with light level instead of jumping between presets.
    //
    // These are tuned starting points, not measured hardware limits - adjust to taste.
    private static final int MIN_ISO_NORMALIZED = 100; // floor we always try first (ISO-100 basis)
    private static final double CAP_RAMP_STOPS = 4.0;  // stops of extra darkness to slide *_START -> *_END

    private static final long PHOTO_HANDHELD_CAP_START = ExposureIndex.sec / 15; // 1/15s
    private static final long PHOTO_HANDHELD_CAP_END   = ExposureIndex.sec / 8;  // 1/8s

    private static final long NIGHT_HANDHELD_CAP_START = ExposureIndex.sec / 8;  // 1/8s
    private static final long NIGHT_HANDHELD_CAP_END   = ExposureIndex.sec / 3;  // 1/3s

    private static final long TRIPOD_CAP_START = ExposureIndex.sec / 4;          // 1/4s
    private static final long TRIPOD_CAP_END   = ExposureIndex.sec * 2;          // 2s

    public static void setExpo(CaptureRequest.Builder builder, int step, CaptureController captureController) {
        Log.v(TAG, "InputParams: " +
                "expo time:" + ExposureIndex.sec2string(ExposureIndex.time2sec(captureController.mPreviewExposureTime)) +
                " iso:" + captureController.mPreviewIso+ " analog:"+getISOAnalog());
        if(step == 0) fullpairs.clear();
        ExpoPair pair = GenerateExpoPair(step,captureController);
        fullpairs.add(pair);
        Log.v(TAG, "IsoSelected:" + pair.iso +
                " ExpoSelected:" + ExposureIndex.sec2string(ExposureIndex.time2sec(pair.exposure)) + " sec step:" + step + " HDR:" + HDR + " total exposure:" + ExposureIndex.time2sec(pair.exposure)*pair.iso);

        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, pair.exposure);
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, (int)pair.iso);
        lastSelectedExposure = pair.exposure;
    }
    private static double mpy1 = 1.0;
    public static ExpoPair GenerateExpoPair(int step, CaptureController captureController) {
        ExpoPair pair = new ExpoPair(captureController.mPreviewExposureTime, getEXPLOW(), getEXPHIGH(),
                captureController.mPreviewIso, getISOLOW(), getISOHIGH(),getISOAnalog());
        double compensation = Math.pow(2.0,PhotonCamera.getSettings().exposureCompensation);
        pair.normalizeiso100();
        pair.ExpoCompensateLower(1.0/compensation);

        if (PhotonCamera.getSettings().selectedMode == CameraMode.NIGHT)
        {
            mpy1 = 7000.0;
            //if(step%3 == 2) mpy = 1.1;
            //mpy = mpy*1.5;
        } else {
             /*else if(PhotonCamera.getSettings().alignAlgorithm == 1){
                if(step%3 == 1) {
                    pair.curlayer = ExpoPair.exposureLayer.High;
                    mpy = 1.0/1.5;
                }
                if(step%3 == 2) {
                    pair.curlayer = ExpoPair.exposureLayer.Normal;
                    mpy = 1.0;
                }
                if(step%3 == 0) {
                    pair.curlayer = ExpoPair.exposureLayer.Low;
                    mpy = 1.5;
                }
            }*/
            mpy1 = 3000.0;
        }
        if(PhotonCamera.getSettings().selectedMode == CameraMode.MOTION || PhotonCamera.getSettings().selectedMode == CameraMode.RAWVIDEO){
            //mpy1 = 0.0;
            pair.denormalizeSystem();
            return pair;
        }

        // Shutter-Priority / Dynamic Low-Light AE Curve - PHOTO and NIGHT only.
        // MOTION/RAWVIDEO already returned above, so framerate-sensitive capture is
        // never affected. Tripod overrides mode when active since it removes the
        // handshake concern that motivates the (shorter) handheld ceilings below.
        long capStart, capEnd;
        if (useTripod) {
            capStart = TRIPOD_CAP_START;
            capEnd = TRIPOD_CAP_END;
        } else if (PhotonCamera.getSettings().selectedMode == CameraMode.NIGHT) {
            capStart = NIGHT_HANDHELD_CAP_START;
            capEnd = NIGHT_HANDHELD_CAP_END;
        } else {
            capStart = PHOTO_HANDHELD_CAP_START;
            capEnd = PHOTO_HANDHELD_CAP_END;
        }
        pair.applyShutterPriorityCurve(capStart, capEnd, CAP_RAMP_STOPS);

        if (pair.normalizedIso() >= 12700.0/mpy1) {
            pair.ReduceIso();
        }
        if (useTripod) {
            // pair.UseIso(Math.max(pair.isoanalog/6.0,101)); // Replaced by applyShutterPriorityCurve
        }

        double currentManExp = captureController.getParamController().getCurrentExposureValue();
        double currentManISO = captureController.getParamController().getCurrentISOValue();
        pair.exposure = currentManExp != 0 ? (long) currentManExp : pair.exposure;
        pair.iso = currentManISO != 0 ? (int) (currentManISO * 100.0 / pair.isolow) : pair.iso;
        pair.curlayer = ExpoPair.exposureLayer.Normal;
        /*if (step%patternSize == 1 && HDR) {
            pair.ExpoCompensateLower(2.0 / 1.0);
            pair.curlayer = ExpoPair.exposureLayer.Low;
        }*/
        /*if(HDR) {
            pair.ExpoCompensateLowerExpo(2.f);
            pair.ExpoCompensateLower(1.f/2.f);
        }*/
        if (step%patternSize == 0 && HDR) {
            // Set multiplier based on bracketing mode (0=Off, 1=Normal, 2=High)
            int bracketingMode = PreferenceKeys.getBracketingMode();
            pair.layerMpy = 1.f;
            if (bracketingMode == 1) {
                // Normal bracketing (1x, 4x)
                pair.layerMpy = 4.f;
            } else if (bracketingMode == 2) {
                // High bracketing (1x, 8x)
                pair.layerMpy = 8.f;
            }
            
            if (pair.layerMpy > 1.f) {
                pair.curlayer = ExpoPair.exposureLayer.High;
                if (pair.ExpoCompensateLowerExpo2(1.0 / pair.layerMpy)) {
                    pair.layerMpy = 1.f;
                    pair.curlayer = ExpoPair.exposureLayer.Normal;
                }
            } else {
                pair.curlayer = ExpoPair.exposureLayer.Normal;
            }
        }
        if ((step%patternSize == 1) && HDR) {
            pair.layerMpy = 1.f;
            pair.ExpoCompensateLowerExpo2(1.0 / pair.layerMpy);
            pair.curlayer = ExpoPair.exposureLayer.Normal;
        }
        if (step%patternSize == 2 && HDR) {
            pair.layerMpy = 1.f;
            pair.ExpoCompensateLowerExpo2(1.0 / pair.layerMpy);
            pair.curlayer = ExpoPair.exposureLayer.Normal;
        }

        if (pair.exposure < ExposureIndex.sec / 90 && PhotonCamera.getSettings().eisPhoto) {
            //HDR = true;
        }
        
        if(step != -1) {
            if (step == 0) pairs.clear();
            if (pairs.size() < patternSize) {
                Log.d(TAG, "Added pair:" + pairs.size());
                pairs.add(pair);
            }
        }
        pair.denormalizeSystem();
        return pair;
    }

    public static double getMPY() {
        return 100.0 / getISOLOW();
    }

    private static int mpyIso(int in) {
        return (int) (in * getMPY());
    }

    private static int getISOHIGH() {
        Object key = CaptureController.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (key == null) return 3200;
        else {
            return (int) ((Range) (key)).getUpper();
        }
    }

    public static int getISOHIGHExt() {
        return mpyIso(getISOHIGH());
    }

    private static int getISOLOW() {
        Object key = CaptureController.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (key == null) return 100;
        else {
            return (int) ((Range) (key)).getLower();
        }
    }
    public static int getISOAnalog() {
        Object key = CaptureController.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY);
        if (key == null) return 100;
        else {
            return (int)(key);
        }
    }

    public static int getISOLOWExt() {
        return mpyIso(getISOLOW());
    }

    public static long getEXPHIGH() {
        Object key = CaptureController.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (key == null) return ExposureIndex.sec;
        else {
            return (long) ((Range) (key)).getUpper();
        }
    }

    public static long getEXPLOW() {
        Object key = CaptureController.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (key == null) return ExposureIndex.sec / 1000;
        else {
            return (long) ((Range) (key)).getLower();
        }
    }


    //==================================Class : ExpoPair==================================//

    public static class ExpoPair {
        public enum exposureLayer{
            Low,
            Normal,
            High
        }
        public exposureLayer curlayer;
        public float layerMpy = 1.f;
        public long exposure;
        public int iso;
        long exposurehigh, exposurelow;
        int isolow, isohigh,isoanalog;

        public ExpoPair(ExpoPair pair) {
            copyfrom(pair);
        }

        public ExpoPair(long expo, long expl, long exph, int is, int islow, int ishigh, int analog) {
            exposure = expo;
            iso = is;
            exposurehigh = exph;
            exposurelow = expl;
            isolow = islow;
            isohigh = ishigh;
            isoanalog = analog;
        }
        public double Exposure(){
            return ExposureIndex.time2sec(exposure)*iso;
        }
        public void copyfrom(ExpoPair pair) {
            exposure = pair.exposure;
            exposurelow = pair.exposurelow;
            exposurehigh = pair.exposurehigh;
            iso = pair.iso;
            isolow = pair.isolow;
            isohigh = pair.isohigh;
            isoanalog = pair.isoanalog;
        }

        public void normalizeiso100() {
            double mpy = 100.0 / isolow;
            iso *= mpy;
            isoanalog *=mpy;
        }

        public void denormalizeSystem() {
            double div = 100.0 / isolow;
            iso /= div;
            isoanalog /=div;
        }
        public float normalizedIso(){
            return (float)iso/isoanalog;
        }
        public void normalize() {
            double div = 100.0 / isolow;
            if (iso / div > isohigh) iso = isohigh;
            if (iso / div < isolow) iso = isolow;
            if (exposure > exposurehigh) exposure = exposurehigh;
            if (exposure < exposurelow) exposure = exposurelow;
        }

        public boolean normalizeCheck() {
            double div = 100.0 / isolow;
            boolean wrongparams = false;
            if (iso / div > isohigh) wrongparams = true;
            if (iso / div < isolow) wrongparams = true;
            if (exposure > exposurehigh) wrongparams = true;
            if (exposure < exposurelow) wrongparams = true;
            return wrongparams;
        }

        public void normalizeISO(){
            double div = 100.0 / isolow;
            if (iso / div > isohigh) {
                double mpy = (iso / div) / isohigh;
                exposure = (long) (exposure * mpy);
                iso = isohigh;
            }
        }

        public void ExpoCompensateLower(double k) {
            iso /= k;
            normalizeISO();
            if (normalizeCheck()) {
                iso *= k;
                exposure /= k;
                if (normalizeCheck()) {
                    exposure *= k;
                    layerMpy = 1.f;
                }
            }
        }

        /**
         * Shutter-Priority / Dynamic Low-Light AE curve (HDR+ Enhanced style).
         *
         * Keeps the platform's own metered brightness target (exposure * iso stays
         * constant) but re-splits it between shutter time and ISO gain: ISO is tried
         * at its minimum first, and only raised once the per-frame shutter time would
         * need to exceed a cap. That cap itself is not fixed - it slides from capStart
         * up to capEnd as the metered scene gets darker (rampStops controls how many
         * stops of extra darkness the full slide takes), which is what makes this a
         * *dynamic* low-light strategy rather than a single handheld/night/tripod
         * threshold switch. The per-mode+tripod shutter ceiling is never exceeded,
         * even in extreme edge cases (e.g. large +exposure compensation in near-total
         * darkness) - if max ISO still isn't enough at that point, the frame comes out
         * a little short of the requested brightness rather than surprising the user
         * with a handheld shot far slower than the active mode calls for.
         *
         * @param capStart  per-frame shutter time where we start extending past minimum ISO
         * @param capEnd    per-frame shutter time ceiling in the darkest scenes
         * @param rampStops how many stops darker than capStart's "just enough" point it
         *                  takes to reach capEnd
         */
        public void applyShutterPriorityCurve(long capStart, long capEnd, double rampStops) {
            double totalExposureEnergy = (double) exposure * iso; // proxy for scene darkness: bigger = darker

            // Energy capStart can already deliver at minimum ISO - past this point,
            // minimum ISO alone is no longer enough to hit the metered brightness.
            double energyAtCapStart = (double) capStart * MIN_ISO_NORMALIZED;

            long dynamicCap;
            if (totalExposureEnergy <= energyAtCapStart) {
                dynamicCap = capStart; // plenty of light, no need to extend the shutter at all
            } else {
                double stopsPastStart = log2(totalExposureEnergy / energyAtCapStart);
                double t = Math.max(0.0, Math.min(1.0, stopsPastStart / rampStops));
                dynamicCap = (long) (capStart * Math.pow((double) capEnd / capStart, t)); // geometric slide
            }

            // Shutter-priority allocation within the dynamic cap.
            iso = MIN_ISO_NORMALIZED;
            exposure = (long) (totalExposureEnergy / iso);
            if (exposure > dynamicCap) {
                exposure = Math.min(dynamicCap, exposurehigh);
                iso = (int) (totalExposureEnergy / exposure);
            }

            // Safety clamp, done by hand in normalized-ISO-100 units. Deliberately NOT
            // calling normalize()/normalizeISO() here: those assign the raw isohigh
            // bound straight into this normalized field, which only happens to be
            // unit-correct when the sensor's isolow is exactly 100. Also deliberately
            // NOT extending exposure past dynamicCap to chase the ISO ceiling further -
            // see the shortfall note above.
            double isoHighNormalized = isohigh * (100.0 / isolow);
            if (iso > isoHighNormalized) iso = (int) isoHighNormalized;
            if (iso < MIN_ISO_NORMALIZED) iso = MIN_ISO_NORMALIZED;
            if (exposure > exposurehigh) exposure = exposurehigh;
            if (exposure < exposurelow) exposure = exposurelow;

            Log.v(TAG, "ShutterPriorityCurve: energy=" + (long) totalExposureEnergy +
                    " dynamicCap=" + ExposureIndex.sec2string(ExposureIndex.time2sec(dynamicCap)) +
                    " -> exposure=" + ExposureIndex.sec2string(ExposureIndex.time2sec(exposure)) +
                    " iso=" + iso);
        }

        private static double log2(double x) {
            return Math.log(x) / Math.log(2.0);
        }

        public void ExpoCompensateLowerExpo(double k) {
            iso /= k;
            if (normalizeCheck()) {
                iso *= k;
                exposure /= k;
                if(normalizeCheck()){
                    exposure *= k;
                    exposure /= Math.sqrt(k);
                    iso /= Math.sqrt(k);
                    if (normalizeCheck()) {
                        exposure *= Math.sqrt(k);
                        iso *= Math.sqrt(k);
                    }
                }
            }
        }

        public boolean ExpoCompensateLowerExpo2(double k) {
            exposure /= k;
            if (normalizeCheck()) {
                exposure *= k;
                iso /= k;
                if(normalizeCheck()){
                    iso *= k;
                    iso /= Math.sqrt(k);
                    exposure /= Math.sqrt(k);
                    if (normalizeCheck()) {
                        iso *= Math.sqrt(k);
                        exposure *= Math.sqrt(k);
                    }
                }
            }
            return normalizeCheck();
        }

        public void MinIso() {
            UseIso(100);
        }

        public void UseIso(double isoUsed) {
            double k = iso / isoUsed;
            ReduceIso(k);
            if (normalizeCheck()) {
                iso *= (double) (exposure) / exposurehigh;
                exposure = exposurehigh;
                if (normalizeCheck()) {
                    iso = isohigh;
                }
            }
        }

        public void ReduceIso() {
            ReduceIso(2.0);
            if (normalizeCheck()) {
                ReduceIso(1.0 / 2);
            }
        }

        public void ReduceIso(double k) {
            iso /= k;
            exposure *= k;
        }

        public void ReduceExpo() {
            ReduceExpo(2.0);
            if (normalizeCheck()) ReduceExpo(1.0 / 2);
        }

        public void ReduceExpo(double k) {
            Log.d(TAG, "ExpoReducing iso:" + iso + " expo:" + ExposureIndex.sec2string(ExposureIndex.time2sec(exposure)));
            iso *= k;
            exposure /= k;
            Log.d(TAG, "ExpoReducing done iso:" + iso + " expo:" + ExposureIndex.sec2string(ExposureIndex.time2sec(exposure)));
        }

        public void FixedExpo(double expo) {
            long expol = ExposureIndex.sec2time(expo);
            double k = (double) exposure / expol;
            ReduceExpo(k);
            Log.d(TAG, "ExpoFixating iso:" + iso + " expo:" + ExposureIndex.sec2string(ExposureIndex.time2sec(exposure)));
            if (normalizeCheck()) ReduceExpo(1 / k);
        }

        public String ExposureString() {
            return ExposureIndex.sec2string(ExposureIndex.time2sec(exposure));
        }
    }
}
