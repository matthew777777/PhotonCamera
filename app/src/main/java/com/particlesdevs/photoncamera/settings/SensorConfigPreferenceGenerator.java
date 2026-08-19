package com.particlesdevs.photoncamera.settings;

import android.content.Context;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import android.text.InputType;
import com.particlesdevs.photoncamera.util.Log;
import com.particlesdevs.photoncamera.settings.annotations.SensorConfig;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableSeekBarPreference;
import java.lang.reflect.Field;

/**
 * Automatically generates preference UI from @SensorConfig annotations.
 * Scans registered classes and creates preference entries scoped per sensor.
 */
public class SensorConfigPreferenceGenerator {
    private static final String TAG = "SensorConfigPrefGen";

    /**
     * Generate many preferences for the sensor config submenu.
     */
    public static void generatePreferences(Context context, PreferenceScreen preferenceScreen) {
        // Try to find the sensor config submenu by key
        PreferenceScreen sensorConfigSubmenu = preferenceScreen.findPreference("pref_sensor_config_submenu");

        if (sensorConfigSubmenu == null) {
            Log.w(TAG, "Sensor config submenu not found! Sensor config preferences will not be generated.");
            return;
        }

        String sensorId = PreferenceKeys.getCameraID();
        Log.d(TAG, "Generating sensor config UI for sensor ID: " + sensorId);

        try {
            // Group preferences by class to keep them organized
            for (Class<?> clazz : SensorConfigRegistry.SENSOR_CONFIG_CLASSES) {
                String className = clazz.getSimpleName();
                PreferenceCategory category = null;

                for (Field field : clazz.getDeclaredFields()) {
                    if (field.isAnnotationPresent(SensorConfig.class)) {
                        if (category == null) {
                            category = new PreferenceCategory(context);
                            category.setTitle("Sensor " + sensorId + " - " + className);
                            sensorConfigSubmenu.addPreference(category);
                        }

                        SensorConfig annotation = field.getAnnotation(SensorConfig.class);
                        if (annotation == null) continue;

                        String prefKey = "pref_sensorconfig_" + sensorId + "_" + field.getName().toLowerCase();

                        if (annotation.step() == 0f) {
                            addFreeTextPreference(context, category, prefKey, field, annotation);
                        } else {
                            addSeekBarPreference(context, category, prefKey, annotation);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error generating sensor config preferences", e);
        }
    }

    private static void addSeekBarPreference(Context context, PreferenceCategory category, String prefKey, SensorConfig annotation) {
        TunableSeekBarPreference seekBar = new TunableSeekBarPreference(context);
        seekBar.setKey(prefKey);
        seekBar.setTitle(annotation.title());

        if (!annotation.description().isEmpty()) {
            seekBar.setSummary(annotation.description());
        }

        seekBar.setMinValue(annotation.min());
        seekBar.setMaxValue(annotation.max());

        float step = annotation.step();
        boolean isFloat = (step != Math.floor(step));
        seekBar.setIsFloat(isFloat);
        seekBar.setStepPerUnit(step > 0 ? 1.0f / step : 1.0f);

        float defaultValue = annotation.defaultValue();
        if (defaultValue != -999999f) {
            seekBar.setDefaultValue(defaultValue);
        }

        category.addPreference(seekBar);
    }

    private static void addFreeTextPreference(Context context, PreferenceCategory category, String prefKey, Field field, SensorConfig annotation) {
        EditTextPreference editText = new EditTextPreference(context);
        editText.setKey(prefKey);
        editText.setTitle(annotation.title());
        editText.setDialogTitle(annotation.title());
        editText.setIconSpaceReserved(false);

        Class<?> fieldType = field.getType();
        int inputType;
        if (fieldType == float.class || fieldType == Float.class || fieldType == double.class || fieldType == Double.class) {
            inputType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        } else {
            inputType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED;
        }

        editText.setOnBindEditTextListener(edit -> edit.setInputType(inputType));

        float def = annotation.defaultValue();
        String description = annotation.description();
        editText.setSummaryProvider(preference -> {
            String value = editText.getText();
            String display = value != null ? value : (fieldType == float.class || fieldType == Float.class ? String.valueOf(def) : String.valueOf((int) def));
            return description.isEmpty() ? display : description + "\n" + display;
        });

        category.addPreference(editText);
    }
}
