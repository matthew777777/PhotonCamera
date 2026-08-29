package com.particlesdevs.photoncamera.processing.parameters;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import com.particlesdevs.photoncamera.util.Log;
import android.util.Range;
import android.util.SizeF;

import com.particlesdevs.photoncamera.api.CameraMode;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import java.util.ArrayList;
import java.util.Locale;

public class IsoExpoSelector {
    public static final int baseFrame = 1;
    /**
     * Capture profiles.  These values are persisted, so do not reorder them.
     *
     * Profiles are planned for the whole burst, rather than repeated every
     * three frames. Base-exposure reference frames are captured first; the one
     * or two longer shadow frames are captured last.
     */
    public static final int BRACKETING_OFF = 0;
    public static final int BRACKETING_HDR = 1;
    public static final int BRACKETING_DEEP_SHADOWS = 2;
    public static final int BRACKETING_CLEAN_SHADOWS = 3;
    private static final long FLICKER_SAFE_50HZ_WINDOW_NS = ExposureIndex.sec / 100;
    private static final long FLICKER_SAFE_60HZ_WINDOW_NS = ExposureIndex.sec / 120;
    private static final String TAG = "IsoExpoSelector";
    public static boolean HDR = false;
    public static boolean useTripod = false;
    public static final int patternSize = 3;
    public static ArrayList<ExpoPair> pairs = new ArrayList<>();
    public static ArrayList<ExpoPair> fullpairs = new ArrayList<>();
    public static long lastSelectedExposure = 0;
    private static BracketingPlan activeBracketingPlan = BracketingPlan.constant(1);
    private static volatile int detectedFlickerHz = 50;

    private static final class BracketingPlan {
        final float[] multipliers;
        final boolean flickerSafe;

        private BracketingPlan(float[] multipliers, boolean flickerSafe) {
            this.multipliers = multipliers;
            this.flickerSafe = flickerSafe;
        }

        static BracketingPlan constant(int frameCount) {
            return new BracketingPlan(new float[Math.max(frameCount, 1)], false);
        }

        float multiplierAt(int step) {
            if (step < 0 || step >= multipliers.length) return 1.f;
            return multipliers[step] > 0.f ? multipliers[step] : 1.f;
        }
    }

    /**
     * Creates one exposure plan per capture.  The final frame(s), not every
     * third frame, receive the long exposure. This keeps a run of base RAW
     * frames available as the sharp alignment reference.
     */
    public static void prepareBracketingPlan(int frameCount) {
        int count = Math.max(frameCount, 1);
        float[] multipliers = new float[count];
        int profile = HDR ? PreferenceKeys.getBracketingMode() : BRACKETING_OFF;
        int shadowFrames = 0;
        float shadowMultiplier = 1.f;
        // Every active bracketing profile uses the preview-detected mains
        // cadence. Off deliberately leaves normal single-exposure capture alone.
        boolean flickerSafe = profile != BRACKETING_OFF;

        switch (profile) {
            case BRACKETING_HDR:
                shadowFrames = 1;
                shadowMultiplier = 4.f;
                break;
            case BRACKETING_DEEP_SHADOWS:
                shadowFrames = 1;
                shadowMultiplier = 8.f;
                break;
            case BRACKETING_CLEAN_SHADOWS:
                shadowFrames = 2;
                shadowMultiplier = 4.f;
                break;
            case BRACKETING_OFF:
            default:
                break;
        }

        // Always retain one short frame. A two-long-frame plan also needs at
        // least two short frames for robust alignment and deghosting.
        int minReferences = shadowFrames > 1 ? 2 : 1;
        shadowFrames = Math.min(shadowFrames, Math.max(0, count - minReferences));
        for (int i = count - shadowFrames; i < count; i++) {
            multipliers[i] = shadowMultiplier;
        }
        activeBracketingPlan = new BracketingPlan(multipliers, flickerSafe);
        Log.d(TAG, "Bracketing plan: frames=" + count + " profile=" + profile
                + " shadowFrames=" + shadowFrames + " multiplier=" + shadowMultiplier
                + " flickerSafe=" + flickerSafe);
    }

    /**
     * Receives the camera HAL's scene-flicker classification from the live
     * preview. Unknown and no-flicker results retain the last stable value;
     * this keeps the capture cadence from changing when a momentary preview
     * result omits the optional statistic.
     */
    public static void updatePreviewFlicker(CaptureResult result) {
        Integer flicker = result.get(CaptureResult.STATISTICS_SCENE_FLICKER);
        if (flicker == null) return;
        int frequency;
        if (flicker == CaptureResult.STATISTICS_SCENE_FLICKER_50HZ) {
            frequency = 50;
        } else if (flicker == CaptureResult.STATISTICS_SCENE_FLICKER_60HZ) {
            frequency = 60;
        } else {
            return;
        }
        if (detectedFlickerHz != frequency) {
            detectedFlickerHz = frequency;
            Log.d(TAG, "Preview flicker detected: " + detectedFlickerHz + " Hz");
        }
    }

    // ---- Shutter-Priority / Dynamic Low-Light AE Curve ----
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
    private static final double CLEAN_ISO_STEP_FACTOR = 2.0; // hardware analog gain stages are conventionally doublings of the base ISO

    private static final long PHOTO_HANDHELD_CAP_START = ExposureIndex.sec / 30; // 1/30s
    private static final long PHOTO_HANDHELD_CAP_END   = ExposureIndex.sec / 15; // 1/15s

    private static final long MOTION_HANDHELD_CAP_START = ExposureIndex.sec / 250; // 1/250s
    private static final long MOTION_HANDHELD_CAP_END   = ExposureIndex.sec / 125; // 1/125s

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
        if(PhotonCamera.getSettings().selectedMode == CameraMode.RAWVIDEO){
            //mpy1 = 0.0;
            pair.denormalizeSystem();
            return pair;
        }

        // Dynamically update tripod state from gyro to avoid stale exposure caching across modes
        if (PhotonCamera.getGyro() != null) {
            useTripod = PhotonCamera.getGyro().getTripod();
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
        } else if (PhotonCamera.getSettings().selectedMode == CameraMode.MOTION) {
            capStart = MOTION_HANDHELD_CAP_START;
            capEnd = MOTION_HANDHELD_CAP_END;
        } else {
            capStart = PHOTO_HANDHELD_CAP_START;
            capEnd = PHOTO_HANDHELD_CAP_END;
        }

        double dynamicFactor = getDynamicScalingFactor();
        capStart = (long) (capStart * dynamicFactor);
        capEnd = (long) (capEnd * dynamicFactor);

        if (PhotonCamera.getSettings().selectedMode == CameraMode.PHOTO && !useTripod) {
            capEnd = Math.min(capEnd, ExposureIndex.sec / 15);
            capStart = Math.min(capStart, capEnd);
        }
        if (PhotonCamera.getSettings().selectedMode == CameraMode.MOTION && !useTripod) {
            capEnd = Math.min(capEnd, ExposureIndex.sec / 60);
            capStart = Math.min(capStart, capEnd);
        }

        pair.applyShutterPriorityCurve(capStart, capEnd, CAP_RAMP_STOPS);

        if (pair.normalizedIso() >= 12700.0/mpy1) {
            pair.ReduceIso();
        }
        if (useTripod) {
            // pair.UseIso(Math.max(pair.isoanalog/6.0,101)); // Replaced by applyShutterPriorityCurve
        }

        // Apply dynamic exposure balance shifting and hard limits (shutter/ISO priority)
        if (captureController != null) {
            float mult = captureController.exposureBalanceMultiplier;
            int isoLimit = captureController.exposureBalanceIsoLimit;
            float shutterLimit = captureController.exposureBalanceShutterLimit;
            CameraMode mode = PhotonCamera.getSettings().selectedMode;
            
            boolean hasMultiplier = (mult != 1.0f);
            boolean hasIsoLimit = (isoLimit != -1);
            boolean hasShutterLimit = (shutterLimit > 0.0f || shutterLimit == -2.0f);

            if ((hasMultiplier || hasIsoLimit || hasShutterLimit) && (mode == CameraMode.PHOTO || mode == CameraMode.NIGHT)) {
                pair.applyExposureBalance(mult, isoLimit, shutterLimit);
            }
        }

        double currentManExp = captureController.getParamController().getCurrentExposureValue();
        double currentManISO = captureController.getParamController().getCurrentISOValue();

        if (currentManExp != 0) {
            pair.exposure = (long) currentManExp;
            pair.isShutterLimited = false;
            pair.isShutterTripodBypassed = false;
            if (!useTripod && captureController != null) {
                long limit = pair.resolveShutterLimit(captureController.exposureBalanceShutterLimit, captureController);
                if (limit < pair.exposurehigh && pair.exposure > limit) {
                    pair.isShutterManualOverLimit = true;
                }
            }
        }

        if (currentManISO != 0) {
            pair.iso = (int) (currentManISO * 100.0 / pair.isolow);
            pair.isIsoLimited = false;
            if (captureController != null && captureController.exposureBalanceIsoLimit != -1) {
                if (pair.iso > pair.resolveIsoLimit(captureController.exposureBalanceIsoLimit)) {
                    pair.isIsoManualOverLimit = true;
                }
            }
        }

        pair.curlayer = ExpoPair.exposureLayer.Normal;
        /*if (step%patternSize == 1 && HDR) {
            pair.ExpoCompensateLower(2.0 / 1.0);
            pair.curlayer = ExpoPair.exposureLayer.Low;
        }*/
        /*if(HDR) {
            pair.ExpoCompensateLowerExpo(2.f);
            pair.ExpoCompensateLower(1.f/2.f);
        }*/
        if (HDR && step >= 0) {
            applyBracketingMultiplier(pair, activeBracketingPlan.multiplierAt(step));
            if (activeBracketingPlan.flickerSafe) {
                applyFlickerSafeExposure(pair);
            }
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

    private static void applyBracketingMultiplier(ExpoPair pair, float multiplier) {
        pair.layerMpy = multiplier;
        if (multiplier <= 1.f) {
            pair.curlayer = ExpoPair.exposureLayer.Normal;
            return;
        }

        pair.curlayer = ExpoPair.exposureLayer.High;
        if (pair.ExpoCompensateLowerExpo2(1.0 / multiplier)) {
            // A device limit prevented the requested bracket; make its metadata
            // truthful so the merger never exposure-scales the frame incorrectly.
            pair.layerMpy = 1.f;
            pair.curlayer = ExpoPair.exposureLayer.Normal;
        }
    }

    /**
     * Keeps an HDR burst phase-neutral under the mains frequency classified by
     * the preview HAL. 50 Hz lighting uses 10 ms, 20 ms, 30 ms (1/100 s,
     * 1/50 s, 1/33.333 s), etc.; 60 Hz lighting uses 1/120 s windows.
     * ISO is changed to keep the metered exposure energy unchanged. If no such
     * shutter is possible within the sensor's shutter and ISO ranges, leave the
     * metered pair untouched rather than clipping it.
     */
    private static void applyFlickerSafeExposure(ExpoPair pair) {
        long flickerWindowNs = detectedFlickerHz == 60
                ? FLICKER_SAFE_60HZ_WINDOW_NS : FLICKER_SAFE_50HZ_WINDOW_NS;
        long targetEnergy = pair.exposure * (long) pair.iso;
        long maxNormalizedIso = Math.round(pair.isohigh * (100.0 / pair.isolow));
        long minExposure = Math.max(pair.exposurelow, ceilDivide(targetEnergy, maxNormalizedIso));
        long maxExposure = Math.min(pair.exposurehigh, targetEnergy / MIN_ISO_NORMALIZED);
        long minWindows = ceilDivide(minExposure, flickerWindowNs);
        long maxWindows = maxExposure / flickerWindowNs;
        if (minWindows > maxWindows || maxWindows < 1) {
            Log.d(TAG, "Flicker-safe shutter unavailable; preserving " + pair.ExposureString());
            return;
        }

        long requestedWindows = Math.round((double) pair.exposure / flickerWindowNs);
        long windows = Math.max(minWindows, Math.min(Math.max(requestedWindows, 1), maxWindows));
        pair.exposure = windows * flickerWindowNs;
        pair.iso = (int) Math.round((double) targetEnergy / pair.exposure);
        Log.d(TAG, "Flicker-safe " + detectedFlickerHz + " Hz exposure: "
                + pair.ExposureString() + " ISO " + pair.iso);
    }

    private static long ceilDivide(long dividend, long divisor) {
        return dividend / divisor + (dividend % divisor == 0 ? 0 : 1);
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

    private static double getDynamicScalingFactor() {
        // 1. Focal Length Scaling
        double focalLength35mm = 24.0;
        CameraCharacteristics characteristics = CaptureController.mCameraCharacteristics;
        if (characteristics != null) {
            float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            SizeF sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (focalLengths != null && focalLengths.length > 0 && sensorSize != null) {
                // Approximate 35mm equivalent: (36mm / sensorWidth) * focalLength
                focalLength35mm = (36.0f / sensorSize.getWidth()) * focalLengths[0];
            }
        }

        // Digital zoom factor
        float zoom = 1.0f;
        CaptureResult result = CaptureController.mPreviewCaptureResult;
        if (result != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Float zoomRatio = result.get(CaptureResult.CONTROL_ZOOM_RATIO);
                if (zoomRatio != null) zoom = zoomRatio;
            } else {
                Rect crop = result.get(CaptureResult.SCALER_CROP_REGION);
                Rect activeArray = characteristics != null ? characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) : null;
                if (crop != null && activeArray != null && crop.width() > 0) {
                    zoom = (float) activeArray.width() / crop.width();
                }
            }
        }
        double effectiveFocalLength = focalLength35mm * zoom;
        // Reciprocal rule baseline (24mm wide). Longer focal length -> smaller factor -> faster shutter.
        double focalFactor = 24.0 / Math.max(effectiveFocalLength, 10.0);

        // 2. Stability Scaling (only if not on a tripod)
        double stabilityFactor = 1.0;
        if (!useTripod && PhotonCamera.getGyro() != null) {
            int shakiness = PhotonCamera.getGyro().getFilteredShakiness();
            if (shakiness > 0) {
                // Steady hands (shakiness ~25) -> up to 4x factor.
                // Shaky hands (shakiness ~400) -> down to 0.25x factor.
                stabilityFactor = 100.0 / Math.max(shakiness, 25);

                // For Motion mode, we must be conservative to avoid subject blur.
                if (PhotonCamera.getSettings().selectedMode == CameraMode.MOTION) {
                    stabilityFactor = Math.min(stabilityFactor, 1.2);
                }
            }
        }

        double combined = focalFactor * stabilityFactor;
        // Clamp total scaling to [0.2x, 2.5x] range to avoid extreme/impossible shutter speeds.
        double finalFactor = Math.max(0.2, Math.min(combined, 2.5));
        Log.v(TAG, "Dynamic AE Factor: " + String.format(Locale.US, "%.2f", finalFactor) +
                " (Focal=" + String.format(Locale.US, "%.2f", effectiveFocalLength) + "mm, " +
                "Stability=" + (PhotonCamera.getGyro() != null ? PhotonCamera.getGyro().getFilteredShakiness() : "N/A") + ")");
        return finalFactor;
    }

    private static long getAutoSafeShutterNs(CaptureController captureController) {
        double efl = 24.0;
        boolean oisActive = false;

        CameraCharacteristics characteristics = CaptureController.mCameraCharacteristics;
        if (characteristics != null) {
            float fl = 4.75f;
            float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (focalLengths != null && focalLengths.length > 0) {
                fl = focalLengths[0];
            }

            SizeF sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (sensorSize != null && sensorSize.getWidth() > 0) {
                efl = (36.0f / sensorSize.getWidth()) * fl;
            }

            // Explicit and safe OIS capability check
            boolean hasHardwareOis = false;
            int[] oisModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            if (oisModes != null) {
                for (int mode : oisModes) {
                    if (mode == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                        hasHardwareOis = true;
                        break;
                    }
                }
            }

            if (hasHardwareOis) {
                oisActive = (captureController == null || captureController.oisMode != 2);
            }
        }

        if (efl <= 0.0) efl = 24.0;

        // Reciprocal rule: 8/EFL if OIS is enabled (+3 stops), 1/EFL without OIS
        double safeSec = (oisActive ? 8.0 : 1.0) / efl;
        return (long) (safeSec * ExposureIndex.sec);
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

        public boolean isIsoLimited = false;
        public boolean isShutterLimited = false;
        public boolean isShutterTripodBypassed = false;
        public boolean isIsoManualOverLimit = false;
        public boolean isShutterManualOverLimit = false;

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
         * Shutter-Priority / Dynamic Low-Light AE curve.
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
         * When ISO does need to rise above minimum, it's snapped to the nearest "clean"
         * hardware gain point at or above the bare minimum required - see
         * {@link #snapToCleanIso} - rather than left at whatever continuous value the
         * arithmetic produces, since an off-grid ISO is frequently not pure analog gain
         * on the sensor and reads noisier than a clean stage for no benefit. The trade-off
         * is a slightly shorter exposure than the theoretical maximum (a clean rung is
         * never below the continuous optimum, only ever a bit above it), in exchange for
         * a real, hardware-backed SNR win at whatever ISO we actually land on.
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
            long effectiveCap = Math.min(dynamicCap, exposurehigh); // never ask for more than the sensor allows either

            // Smallest (continuous) ISO that still hits the metered brightness within effectiveCap.
            double isoMinToFit = totalExposureEnergy / effectiveCap;

            if (isoMinToFit <= MIN_ISO_NORMALIZED) {
                iso = MIN_ISO_NORMALIZED; // plenty of light, minimum ISO alone already fits under the cap
            } else {
                long cleanIso = snapToCleanIso(isoMinToFit, true);
                double shutterAtCleanIso = totalExposureEnergy / cleanIso;

                // If snapping up to a "clean" hardware gain stage would drop our shutter time
                // by more than 5% below the cap, prioritize the photon collection (shutter duration)
                // and use the exact ISO required instead.
                if (shutterAtCleanIso < effectiveCap * 0.95) {
                    iso = (int) Math.ceil(isoMinToFit);
                } else {
                    iso = (int) cleanIso;
                }
            }
            exposure = (long) (totalExposureEnergy / iso);

            // Safety clamp, done by hand in normalized-ISO-100 units. Deliberately NOT
            // calling normalize()/normalizeISO() here: those assign the raw isohigh bound
            // straight into this normalized field, which only happens to be unit-correct
            // when the sensor's isolow is exactly 100. Bounding exposure by effectiveCap
            // (not just exposurehigh) keeps the "never exceed the policy cap" guarantee
            // even when snapToCleanIso has to fall back to the sensor's true ISO ceiling.
            if (exposure > effectiveCap) exposure = effectiveCap;
            if (exposure < exposurelow) exposure = exposurelow;
            double isoHighNormalized = isohigh * (100.0 / isolow);
            if (iso > isoHighNormalized) iso = (int) Math.round(isoHighNormalized);
            if (iso < MIN_ISO_NORMALIZED) iso = MIN_ISO_NORMALIZED;

            Log.v(TAG, "ShutterPriorityCurve: energy=" + (long) totalExposureEnergy +
                    " dynamicCap=" + ExposureIndex.sec2string(ExposureIndex.time2sec(dynamicCap)) +
                    " -> exposure=" + ExposureIndex.sec2string(ExposureIndex.time2sec(exposure)) +
                    " iso=" + iso);
        }        

        /**
         * Resolves the effective normalized ISO ceiling based on the configured limit flag/number.
         */
        public double resolveIsoLimit(int isoLimit) {
            if (isoLimit == -4) return Math.max(100.0, (double) isoanalog / 4.0);
            if (isoLimit == -3) return Math.max(100.0, (double) isoanalog / 2.0);
            if (isoLimit == -2) return (double) isoanalog;
            if (isoLimit == -1) return (double) isohigh * (100.0 / isolow);
            return Math.min((double) isohigh, (double) isoLimit) * (100.0 / isolow);
        }

        /**
         * Resolves the effective shutter duration limit in nanoseconds.
         */
        public long resolveShutterLimit(float shutterLimitSec, CaptureController cc) {
            if (shutterLimitSec == -2.0f) return getAutoSafeShutterNs(cc);
            if (shutterLimitSec > 0.0f) return (long) (shutterLimitSec * ExposureIndex.sec);
            return exposurehigh;
        }

        /**
         * Shifts the exposure balance by the given multiplier k (shutter/ISO trade-off).
         * A multiplier > 1.0 reduces shutter duration and increases ISO (freezing motion).
         * A multiplier < 1.0 increases shutter duration and reduces ISO (cleaner image).
         *
         * Uses a Dual-Axis Backtracking Clamping algorithm with Tripod Awareness.
         *
         * @param k               the multiplier to adjust balance
         * @param isoLimit        the configured ISO limit (-1 = Sensor Max, -2 = Max Analog, -3 = Max Analog / 2, -4 = Max Analog / 4, >0 = Custom limit)
         * @param shutterLimitSec the configured shutter duration limit in seconds (-1.0f = Sensor Max, >0 = Custom limit in seconds)
         */
        public void applyExposureBalance(double k, int isoLimit, float shutterLimitSec) {
            isIsoLimited = false;
            isShutterLimited = false;
            isShutterTripodBypassed = false;
            isIsoManualOverLimit = false;
            isShutterManualOverLimit = false;

            // 1. Save target exposure energy
            double targetEnergy = (double) exposure * iso;

            // 2. Apply theoretical shift
            exposure = (long) (exposure / k);
            iso = (int) (iso * k);

            // 3. Resolve bounds using helper methods
            double isoHighNormalized = resolveIsoLimit(isoLimit);
            long userShutterNs = resolveShutterLimit(shutterLimitSec, PhotonCamera.getCaptureController());
            long effectiveExposureHigh = useTripod ? exposurehigh : Math.min(exposurehigh, userShutterNs);

            // 4. ISO limits check with backtracking to exposure
            if (iso > isoHighNormalized) {
                iso = (int) Math.round(isoHighNormalized);
                if (isoLimit != -1) isIsoLimited = true;
                exposure = (long) (targetEnergy / iso);
            } else if (iso < 100) {
                iso = 100;
                exposure = (long) (targetEnergy / iso);
            }

            // 5. Exposure limits check with clean ISO snapping down
            if (exposure > effectiveExposureHigh) {
                exposure = effectiveExposureHigh;
                if ((shutterLimitSec > 0.0f || shutterLimitSec == -2.0f) && !useTripod) isShutterLimited = true;
                double continuousIso = targetEnergy / exposure;
                iso = (int) snapToCleanIso(continuousIso, false);
            } else if (exposure < exposurelow) {
                exposure = exposurelow;
                double continuousIso = targetEnergy / exposure;
                iso = (int) snapToCleanIso(continuousIso, false);
            }

            // 6. Final safety clamps
            if (iso > isoHighNormalized) {
                iso = (int) Math.round(isoHighNormalized);
                if (isoLimit != -1) isIsoLimited = true;
            }
            if (iso < 100) iso = 100;

            if (exposure > effectiveExposureHigh) {
                exposure = effectiveExposureHigh;
                if ((shutterLimitSec > 0.0f || shutterLimitSec == -2.0f) && !useTripod) isShutterLimited = true;
            }
            if (exposure < exposurelow) exposure = exposurelow;

            // 7. Check if tripod mode bypassed the user's handheld shutter limit
            if (useTripod && userShutterNs < exposurehigh && exposure > userShutterNs) {
                isShutterTripodBypassed = true;
                isShutterLimited = false;
            }
        }

        /**
         * Snaps to the nearest ISO the sensor can realize as a clean hardware gain step (normalized ISO-100 basis):
         * the base ISO doubled some number of times, plus the sensor's own reported max-pure-analog gain point
         * ({@code isoanalog} / SENSOR_MAX_ANALOG_SENSITIVITY) inserted as an extra rung even when it doesn't fall
         * on a doubling, since that boundary is real hardware data rather than an assumption about gain-stage spacing.
         * Falls back to the sensor's true ISO ceiling if nothing smaller fits.
         *
         * @param targetIso target normalized ISO value to snap
         * @param snapUp    if true, snaps UP (ceiling, >= targetIso) to guarantee safe exposure duration in auto curves;
         *                  if false, snaps DOWN (floor, <= targetIso) to guarantee pure analog gain without HAL digital
         *                  scaling noise when bounded by shutter limits.
         * @return snapped clean normalized ISO value
         */
        private long snapToCleanIso(double targetIso, boolean snapUp) {
            double isoHighNormalized = isohigh * (100.0 / isolow);
            double isoAnalogNormalized = isoanalog * (100.0 / isolow);

            double[] ladder = new double[16];
            int n = 0;
            for (double rung = MIN_ISO_NORMALIZED; rung <= isoHighNormalized && n < 14; rung *= CLEAN_ISO_STEP_FACTOR) {
                ladder[n++] = rung;
            }
            if (isoAnalogNormalized > MIN_ISO_NORMALIZED && isoAnalogNormalized < isoHighNormalized) {
                ladder[n++] = isoAnalogNormalized;
            }
            ladder[n++] = isoHighNormalized; // true sensor ceiling, always available as a last resort
            java.util.Arrays.sort(ladder, 0, n);

            if (snapUp) {
                for (int i = 0; i < n; i++) {
                    if (ladder[i] >= targetIso) return Math.round(ladder[i]);
                }
                return Math.round(isoHighNormalized);
            } else {
                long result = Math.round(ladder[0]);
                for (int i = 0; i < n; i++) {
                    if (ladder[i] <= targetIso) {
                        result = Math.round(ladder[i]);
                    } else {
                        break;
                    }
                }
                return result;
            }
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
