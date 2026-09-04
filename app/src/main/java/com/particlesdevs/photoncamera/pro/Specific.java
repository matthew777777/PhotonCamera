package com.particlesdevs.photoncamera.pro;

import android.content.Context;
import android.os.Build;
import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.util.HttpLoader;
import com.particlesdevs.photoncamera.util.SimpleStorageHelper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;

public class Specific {
    private static final String TAG = "Specific";

    public boolean isLoaded = false;
    public SpecificSetting specificSetting = new SpecificSetting();
    public float[] blackLevel;
    private final SettingsManager mSettingsManager;

    public Specific(SettingsManager mSettingsManager) {
        this.mSettingsManager = mSettingsManager;
    }

    private static final String DEVICE_SPECIFIC_PATH = "DCIM/PhotonCamera/Tuning/DeviceSpecific.txt";

    ArrayList<String> loadLocal(Context context) throws Exception {
        ArrayList<String> inputStr = new ArrayList<>();
        InputStream is = SimpleStorageHelper.openInputStreamByPath(context, DEVICE_SPECIFIC_PATH);
        BufferedReader indevice = new BufferedReader(new InputStreamReader(is));
        String str;
        while ((str = indevice.readLine()) != null) {
            Log.d(TAG, "read local:" + str);
            inputStr.add(str + "\n");
        }
        indevice.close();
        return inputStr;
    }

    ArrayList<String> loadAssets(Context context, String device) throws IOException {
        ArrayList<String> inputStr = new ArrayList<>();
        String assetPath = "specific/" + device + "_specificsettings.txt";
        InputStream is = context.getAssets().open(assetPath);
        BufferedReader indevice = new BufferedReader(new InputStreamReader(is));
        String str;
        while ((str = indevice.readLine()) != null) {
            Log.d(TAG, "read asset:" + str);
            inputStr.add(str + "\n");
        }
        indevice.close();
        return inputStr;
    }

    ArrayList<String> loadNetwork(String device) throws IOException {
        ArrayList<String> inputStr = new ArrayList<>();
        BufferedReader indevice = HttpLoader.readURL(
                "https://raw.githubusercontent.com/eszdman/PhotonCamera/dev/app/src/main/assets/specific/" + device + "_specificsettings.txt", 100);
        String str;
        while ((str = indevice.readLine()) != null) {
            Log.d(TAG, "read network:" + str);
            inputStr.add(str + "\n");
        }
        return inputStr;
    }

    private void parseAndApply(ArrayList<String> inputStr) {
        for (String str : inputStr) {
            String[] caseS = str.replace(" ", "").replace("\n", "").split("=");
            if (caseS.length < 2) continue;
            switch (caseS[0]) {
                case "isDualSessionSupported":
                    specificSetting.isDualSessionSupported = Boolean.parseBoolean(caseS[1]);
                    break;
                case "blackLevel": {
                    String[] bl = caseS[1].split(",");
                    blackLevel = new float[]{Float.parseFloat(bl[0]), Float.parseFloat(bl[1]),
                            Float.parseFloat(bl[2]), Float.parseFloat(bl[3])};
                    break;
                }
                case "rawColorCorrection":
                    specificSetting.isRawColorCorrection = Boolean.parseBoolean(caseS[1]);
                    break;
                case "cameraIDS": {
                    Log.d(TAG, "Camera IDs Loaded: " + caseS[1]);
                    String[] ids = caseS[1].replace("{", "").replace("}", "").split(",");
                    specificSetting.cameraIDS = new String[ids.length];
                    for (int i = 0; i < specificSetting.cameraIDS.length; i++) {
                        specificSetting.cameraIDS[i] = ids[i];
                    }
                    break;
                }
            }
        }
    }

    public void loadSpecific(Context context) {
        isLoaded = mSettingsManager.getBoolean(PreferenceKeys.Key.DEVICES_PREFERENCE_FILE_NAME.mValue, "specific_loaded", false);
        boolean exists = mSettingsManager.getBoolean(PreferenceKeys.Key.DEVICES_PREFERENCE_FILE_NAME.mValue, "specific_exists", true);
        Log.d(TAG, "loaded: " + isLoaded + " exists: " + exists);
        if (exists) {
            if (!isLoaded) {
                try {
                    String device = Build.BRAND.toLowerCase() + "/" + Build.DEVICE.toLowerCase();
                    ArrayList<String> inputStr = null;

                    if (SimpleStorageHelper.fileExistsByPath(context, DEVICE_SPECIFIC_PATH)) {
                        Log.d(TAG, "Loading from local file");
                        inputStr = loadLocal(context);
                    } else {
                        try {
                            Log.d(TAG, "Loading from assets");
                            inputStr = loadAssets(context, device);
                        } catch (IOException e) {
                            Log.d(TAG, "No asset found for device, skipping network: " + device);
                        }
                    }

                    if (inputStr != null && !inputStr.isEmpty()) {
                        parseAndApply(inputStr);
                        mSettingsManager.set(PreferenceKeys.Key.DEVICES_PREFERENCE_FILE_NAME.mValue, "specific_loaded", true);
                    }
                } catch (Exception e) {
                    Log.e(TAG, e.toString());
                }
            } else {
                specificSetting.isDualSessionSupported = mSettingsManager.getBoolean(
                        PreferenceKeys.Key.DEVICES_PREFERENCE_FILE_NAME.mValue,
                        "specific_is_dual_session", specificSetting.isDualSessionSupported);
            }
            saveSpecific();
        }
        isLoaded = true;
    }

    public void fetchFromNetwork(Context context) {
        try {
            String device = Build.BRAND.toLowerCase() + "/" + Build.DEVICE.toLowerCase();
            Log.d(TAG, "Fetching from network for device: " + device);
            ArrayList<String> inputStr = loadNetwork(device);
            if (!inputStr.isEmpty()) {
                parseAndApply(inputStr);
                mSettingsManager.set(PreferenceKeys.Key.DEVICES_PREFERENCE_FILE_NAME.mValue, "specific_loaded", true);
                saveSpecific();
                Log.d(TAG, "Network fetch successful");
            }
        } catch (Exception e) {
            Log.e(TAG, "Network fetch failed: " + e.toString());
        }
    }

    private void saveSpecific() {
        mSettingsManager.set(PreferenceKeys.Key.DEVICES_PREFERENCE_FILE_NAME.mValue,
                "specific_is_dual_session", specificSetting.isDualSessionSupported);
    }
}
