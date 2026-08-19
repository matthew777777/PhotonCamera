package com.particlesdevs.photoncamera.settings;

import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.settings.annotations.SensorConfig;
import com.particlesdevs.photoncamera.util.Log;

import java.lang.reflect.Field;

/**
 * Runtime injector for @SensorConfig annotated fields.
 * Scoped by sensor ID.
 */
public class SensorConfigInjector {
    private static final String TAG = "SensorConfigInjector";

    public static void applyToSensor(String sensorId, Object target) {
        if (target == null) {
            Log.w(TAG, "Target is null, cannot inject");
            return;
        }

        Class<?> clazz = target.getClass();
        SettingsManager settingsManager = PhotonCamera.getSettingsManagerStatic();
        if (settingsManager == null) {
            Log.w(TAG, "SettingsManager is null, cannot inject");
            return;
        }

        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(SensorConfig.class)) {
                SensorConfig annotation = field.getAnnotation(SensorConfig.class);
                if (annotation == null) continue;

                field.setAccessible(true);
                // Key format: pref_sensorconfig_<sensorId>_<fieldName>
                String prefKey = "pref_sensorconfig_" + sensorId + "_" + field.getName().toLowerCase();

                try {
                    Class<?> fieldType = field.getType();
                    float annotationDefault = annotation.defaultValue();
                    float step = annotation.step();
                    boolean isStoredAsFloat = (step != Math.floor(step));
                    boolean isFreeText = (step == 0f);

                    String freeText = isFreeText
                            ? SettingsManagerExtensions.getString(settingsManager, PreferenceKeys.SCOPE_GLOBAL, prefKey, null)
                            : null;

                    if (fieldType == float.class || fieldType == Float.class) {
                        float def = (annotationDefault == -999999f) ? field.getFloat(target) : annotationDefault;
                        float value;
                        if (isFreeText) {
                            value = (freeText == null || freeText.trim().isEmpty()) ? def : Float.parseFloat(freeText);
                        } else if (isStoredAsFloat) {
                            value = SettingsManagerExtensions.getFloat(settingsManager,
                                    PreferenceKeys.SCOPE_GLOBAL, prefKey, def);
                        } else {
                            value = (float) SettingsManagerExtensions.getInteger(settingsManager,
                                    PreferenceKeys.SCOPE_GLOBAL, prefKey, (int) def);
                        }
                        field.setFloat(target, value);
                        //Log.d(TAG, "Injected " + prefKey + " = " + value);

                    } else if (fieldType == int.class || fieldType == Integer.class) {
                        int def = (annotationDefault == -999999f) ? field.getInt(target) : (int) annotationDefault;
                        int value;
                        if (isFreeText) {
                            value = (freeText == null || freeText.trim().isEmpty()) ? def : Integer.parseInt(freeText);
                        } else {
                            value = SettingsManagerExtensions.getInteger(settingsManager,
                                    PreferenceKeys.SCOPE_GLOBAL, prefKey, def);
                        }
                        field.setInt(target, value);
                        //Log.d(TAG, "Injected " + prefKey + " = " + value);

                    } else if (fieldType == double.class || fieldType == Double.class) {
                        double def = (annotationDefault == -999999f) ? field.getDouble(target) : (double) annotationDefault;
                        double value;
                        if (isFreeText) {
                            value = (freeText == null || freeText.trim().isEmpty()) ? def : Double.parseDouble(freeText);
                        } else if (isStoredAsFloat) {
                            value = (double) SettingsManagerExtensions.getFloat(settingsManager,
                                    PreferenceKeys.SCOPE_GLOBAL, prefKey, (float) def);
                        } else {
                            value = (double) SettingsManagerExtensions.getInteger(settingsManager,
                                    PreferenceKeys.SCOPE_GLOBAL, prefKey, (int) def);
                        }
                        field.setDouble(target, value);
                    } else {
                        Log.w(TAG, "Unsupported type for field: " + field.getName() + " (" + fieldType + ")");
                    }

                } catch (IllegalAccessException e) {
                    Log.e(TAG, "Failed to inject field: " + field.getName(), e);
                } catch (Exception e) {
                    Log.e(TAG, "Error injecting field: " + field.getName(), e);
                }
            }
        }
    }
}
