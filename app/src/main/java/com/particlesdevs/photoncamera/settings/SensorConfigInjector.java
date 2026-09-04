package com.particlesdevs.photoncamera.settings;

import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.render.SpecificSettingSensor;
import com.particlesdevs.photoncamera.settings.annotations.SensorConfig;
import com.particlesdevs.photoncamera.util.Log;

import java.lang.reflect.Field;

import static com.particlesdevs.photoncamera.settings.SettingsManager.SCOPE_GLOBAL;

/**
 * Runtime injector for {@code @SensorConfig} annotated fields.
 * Reads per-sensor values from SharedPreferences and applies them to a
 * {@link SpecificSettingSensor} instance, so tuning works per physical camera id.
 */
public class SensorConfigInjector {
    private static final String TAG = "SensorConfigInjector";

    private SensorConfigInjector() {}

    /**
     * Apply sensor config overrides for a given physical sensor id to the target object.
     *
     * @param sensorId physical camera id (e.g. "0", "1", "2")
     * @param target   the object whose {@code @SensorConfig} fields will be updated
     */
    public static void applyToSensor(String sensorId, Object target) {
        if (target == null || sensorId == null || sensorId.isEmpty()) {
            Log.w(TAG, "Target or sensorId is null, cannot inject");
            return;
        }

        SettingsManager settingsManager = PhotonCamera.getSettingsManagerStatic();
        if (settingsManager == null) {
            Log.w(TAG, "SettingsManager is null, cannot inject");
            return;
        }

        Class<?> clazz = target.getClass();
        String className = clazz.getSimpleName();

        for (Field field : clazz.getDeclaredFields()) {
            if (!field.isAnnotationPresent(SensorConfig.class)) continue;
            SensorConfig annotation = field.getAnnotation(SensorConfig.class);
            if (annotation == null) continue;

            field.setAccessible(true);
            String prefKey = "pref_sensorconfig_" + sensorId + "_" + field.getName().toLowerCase();
            String sensorKey = className + "." + field.getName() + " [" + sensorId + "]";

            try {
                float annotationDefault = annotation.defaultValue();
                if (annotationDefault == -999999f) {
                    annotationDefault = annotation.min();
                }
                float step = annotation.step();
                boolean isStoredAsFloat = (step != Math.floor(step));
                boolean isFreeText = (step == 0f);
                boolean isList = (annotation.entries().length > 0 && annotation.entryValues().length > 0);
                String stringValue = (isFreeText || isList)
                        ? SettingsManagerExtensions.getString(settingsManager, SCOPE_GLOBAL, prefKey, null)
                        : null;

                Class<?> fieldType = field.getType();
                if (fieldType == float.class || fieldType == Float.class) {
                    float value;
                    if (isFreeText || isList) {
                        value = (stringValue == null || stringValue.trim().isEmpty()) ? annotationDefault : Float.parseFloat(stringValue);
                    } else if (isStoredAsFloat) {
                        value = SettingsManagerExtensions.getFloat(settingsManager, SCOPE_GLOBAL, prefKey, annotationDefault);
                    } else {
                        value = (float) SettingsManagerExtensions.getInteger(settingsManager, SCOPE_GLOBAL, prefKey, (int) annotationDefault);
                    }
                    field.setFloat(target, value);
                    Log.d(TAG, "Injected " + sensorKey + " = " + value);
                } else if (fieldType == int.class || fieldType == Integer.class) {
                    int value;
                    if (isFreeText || isList) {
                        value = (stringValue == null || stringValue.trim().isEmpty()) ? (int) annotationDefault : Integer.parseInt(stringValue);
                    } else {
                        value = SettingsManagerExtensions.getInteger(settingsManager, SCOPE_GLOBAL, prefKey, (int) annotationDefault);
                    }
                    field.setInt(target, value);
                    Log.d(TAG, "Injected " + sensorKey + " = " + value);
                } else if (fieldType == double.class || fieldType == Double.class) {
                    double value;
                    if (isFreeText || isList) {
                        value = (stringValue == null || stringValue.trim().isEmpty()) ? annotationDefault : Double.parseDouble(stringValue);
                    } else if (isStoredAsFloat) {
                        value = (double) SettingsManagerExtensions.getFloat(settingsManager, SCOPE_GLOBAL, prefKey, annotationDefault);
                    } else {
                        value = (double) SettingsManagerExtensions.getInteger(settingsManager, SCOPE_GLOBAL, prefKey, (int) annotationDefault);
                    }
                    field.setDouble(target, value);
                    Log.d(TAG, "Injected " + sensorKey + " = " + value);
                } else if (fieldType == boolean.class || fieldType == Boolean.class) {
                    boolean defVal = (annotationDefault != 0.0f);
                    boolean value;
                    if (isFreeText || isList) {
                        value = stringValue == null || stringValue.trim().isEmpty()
                                ? defVal
                                : (Boolean.parseBoolean(stringValue) || stringValue.equals("1"));
                    } else {
                        value = SettingsManagerExtensions.getBoolean(settingsManager, SCOPE_GLOBAL, prefKey, defVal);
                    }
                    field.setBoolean(target, value);
                    Log.d(TAG, "Injected " + sensorKey + " = " + value);
                } else if (fieldType == String.class && (isFreeText || isList)) {
                    String value = (stringValue == null || stringValue.trim().isEmpty())
                            ? String.valueOf((int) annotationDefault) : stringValue;
                    field.set(target, value);
                    Log.d(TAG, "Injected " + sensorKey + " = " + value);
                } else {
                    Log.w(TAG, "Unsupported type for field: " + field.getName() + " (" + fieldType + ")");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to inject field: " + field.getName(), e);
            }
        }
    }
}
