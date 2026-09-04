package com.particlesdevs.photoncamera.settings;

import android.content.Context;
import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.TunablePngPreference;

import java.io.File;
import java.lang.reflect.Field;

/**
 * Runtime injector for @Tunable annotated fields.
 * Automatically reads values from SharedPreferences and injects them into fields.
 */
public class TunableInjector {
    private static final String TAG = "TunableInjector";
    
    /**
     * Inject tunable values into an object's annotated fields
     * @param target The object to inject values into
     */
    public static void inject(Object target) {
        if (target == null) {
            Log.w(TAG, "Target is null, cannot inject");
            return;
        }
        
        Class<?> clazz = target.getClass();
        String className = clazz.getSimpleName();
        
        SettingsManager settingsManager = PhotonCamera.getSettingsManagerStatic();
        if (settingsManager == null) {
            Log.w(TAG, "SettingsManager is null, cannot inject");
            return;
        }
        
        // Iterate through all fields
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(Tunable.class)) {
                Tunable annotation = field.getAnnotation(Tunable.class);
                if (annotation == null) continue;
                
                field.setAccessible(true);
                
                // Generate preference key
                String prefKey = "pref_tunable_" + className.toLowerCase() + "_" + field.getName().toLowerCase();
                
                try {
                    Class<?> fieldType = field.getType();
                    
                    // Get default value from annotation (single source of truth!)
                    float annotationDefault = annotation.defaultValue();
                    if (annotationDefault == -999999f) {
                        annotationDefault = annotation.min();
                    }
                    
                    // Determine if stored as float or int based on step (same logic as TunablePreferenceGenerator)
                    float step = annotation.step();
                    boolean isStoredAsFloat = (step != Math.floor(step));
                    boolean isFreeText = (step == 0f);
                    boolean isList = (annotation.entries().length > 0 && annotation.entryValues().length > 0);
                    String freeText = (isFreeText || isList)
                            ? SettingsManagerExtensions.getString(settingsManager, PreferenceKeys.SCOPE_GLOBAL, prefKey, null)
                            : null;
                    
                    // Inject value based on type (using annotation default)
                    if (fieldType == float.class || fieldType == Float.class) {
                        float value;
                        if (isFreeText) {
                            value = (freeText == null || freeText.trim().isEmpty()) ? annotationDefault : Float.parseFloat(freeText);
                        } else if (isStoredAsFloat) {
                            value = SettingsManagerExtensions.getFloat(settingsManager, 
                                PreferenceKeys.SCOPE_GLOBAL, prefKey, annotationDefault);
                        } else {
                            // Stored as int, convert to float
                            value = (float) SettingsManagerExtensions.getInteger(settingsManager, 
                                PreferenceKeys.SCOPE_GLOBAL, prefKey, (int) annotationDefault);
                        }
                        field.setFloat(target, value);
                        Log.d(TAG, "Injected " + prefKey + " = " + value + " (default: " + annotationDefault + ")");
                        
                    } else if (fieldType == int.class || fieldType == Integer.class) {
                        int value;
                        if (isFreeText) {
                            value = (freeText == null || freeText.trim().isEmpty()) ? (int) annotationDefault : Integer.parseInt(freeText);
                        } else {
                            value = SettingsManagerExtensions.getInteger(settingsManager, 
                                PreferenceKeys.SCOPE_GLOBAL, prefKey, (int) annotationDefault);
                        }
                        field.setInt(target, value);
                        Log.d(TAG, "Injected " + prefKey + " = " + value + " (default: " + (int) annotationDefault + ")");
                        
                    } else if (fieldType == double.class || fieldType == Double.class) {
                        double value;
                        if (isFreeText) {
                            value = (freeText == null || freeText.trim().isEmpty()) ? annotationDefault : Double.parseDouble(freeText);
                        } else if (isStoredAsFloat) {
                            // Stored as float
                            value = (double) SettingsManagerExtensions.getFloat(settingsManager, 
                                PreferenceKeys.SCOPE_GLOBAL, prefKey, annotationDefault);
                        } else {
                            // Stored as int, convert to double
                            value = (double) SettingsManagerExtensions.getInteger(settingsManager, 
                                PreferenceKeys.SCOPE_GLOBAL, prefKey, (int) annotationDefault);
                        }
                        field.setDouble(target, value);
                        Log.d(TAG, "Injected " + prefKey + " = " + value + " (default: " + annotationDefault + ")");
                        
                    } else if (fieldType == boolean.class || fieldType == Boolean.class) {
                        boolean defVal = (annotationDefault != 0.0f);
                        
                        // Check if this is a checkbox preference (min=0, max=1, step=1)
                        // Checkbox preferences store as int (0 or 1), not boolean
                        float min = annotation.min();
                        float max = annotation.max();
                        boolean isCheckbox = (min == 0.0f && max == 1.0f && step == 1.0f);
                        
                        boolean value;
                        if (isFreeText) {
                            value = (freeText == null || freeText.trim().isEmpty())
                                    ? defVal
                                    : (Boolean.parseBoolean(freeText) || freeText.equals("1"));
                            Log.d(TAG, "Injected free text " + prefKey + " = " + value + " (default: " + defVal + ")");
                        } else if (isCheckbox) {
                            // Read as int (0 or 1) and convert to boolean
                            int intDefault = defVal ? 1 : 0;
                            int intValue = SettingsManagerExtensions.getInteger(settingsManager, 
                                PreferenceKeys.SCOPE_GLOBAL, prefKey, intDefault);
                            value = (intValue != 0);
                            Log.d(TAG, "Injected checkbox " + prefKey + " = " + value + " (from int: " + intValue + ", default: " + defVal + ")");
                        } else {
                            // Read as boolean normally
                            value = SettingsManagerExtensions.getBoolean(settingsManager, 
                                PreferenceKeys.SCOPE_GLOBAL, prefKey, defVal);
                            Log.d(TAG, "Injected " + prefKey + " = " + value + " (default: " + defVal + ")");
                        }
                        field.setBoolean(target, value);

                    } else if (fieldType == String.class && isList) {
                        // List selector: stored string, falls back to the field initializer, then entryValues[0]
                        String value = (freeText == null || freeText.trim().isEmpty())
                                ? (String) field.get(target) : freeText;
                        boolean valid = false;
                        for (String val : annotation.entryValues()) {
                            if (val.equals(value)) {
                                valid = true;
                                break;
                            }
                        }
                        if (!valid) value = annotation.entryValues()[0];
                        field.set(target, value);
                        Log.d(TAG, "Injected list " + prefKey + " = " + value);

                    } else if (fieldType == String.class && isFreeText) {
                        String value = (freeText == null || freeText.trim().isEmpty())
                                ? String.valueOf((int) annotationDefault) : freeText;
                        field.set(target, value);
                        Log.d(TAG, "Injected " + prefKey + " = " + value + " (default: " + (int) annotationDefault + ")");

                    } else if (fieldType == File.class) {
                        // Read filename from preferences and construct File in private storage
                        String fileName = settingsManager.getString(
                            PreferenceKeys.SCOPE_GLOBAL, prefKey, null);
                        File file = null;
                        if (fileName != null && !fileName.isEmpty()) {
                            File pngFile = new File(TunablePngPreference.getPngsDir(settingsManager.getContext()), fileName);
                            if (pngFile.exists()) {
                                file = pngFile;
                            }
                        }
                        field.set(target, file);
                        Log.d(TAG, "Injected File " + prefKey + " = " + file);

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

