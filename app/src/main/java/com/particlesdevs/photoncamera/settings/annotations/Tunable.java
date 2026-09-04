package com.particlesdevs.photoncamera.settings.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for automatically generating preference settings for tunable parameters.
 * Just add this annotation to a field and it will appear in the settings UI.
 * 
 * Usage example:
 * <pre>
 * {@code
 * @Tunable(
 *     title = "Blur Size",
 *     description = "Size of the blur kernel",
 *     category = "Sharpening",
 *     min = 0.0f,
 *     max = 2.0f
 * )
 * float blurSize = 0.20f;
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Tunable {
    /**
     * Display title in settings UI
     */
    String title();
    
    /**
     * Optional description/summary for the setting
     */
    String description() default "";
    
    /**
     * Category/group for the setting
     * Options: "Sharpening", "Demosaic", "Denoise", "Color", "Tone", "General"
     */
    String category() default "General";
    
    /**
     * Minimum value (for numeric types)
     */
    float min() default 0.0f;
    
    /**
     * Maximum value (for numeric types)
     */
    float max() default 1.0f;
    
    /**
     * Default value (for numeric types)
     * Use -999999 to indicate "use field's initial value"
     */
    float defaultValue() default -999999f;
    
    /**
     * Step size for seekbar (for numeric types)
     * If step has decimals (e.g. 0.01), it's treated as float
     * If step is whole number (e.g. 1.0), it's treated as integer
     */
    float step() default 0.01f;

    /**
     * Allowed LUT sizes for File type fields (square PNG LUT dimensions).
     * Only used when the annotated field type is {@link java.io.File}.
     * Example: {512, 1000, 1728, 2744, 4096}
     */
    int[] allowedPngSizes() default {};

    /**
     * Optional human-readable labels for ListPreference dialog options.
     * Only used when the annotated field type is {@link String}; if both
     * entries and entryValues are provided, a ListPreference is generated.
     */
    String[] entries() default {};

    /**
     * Values corresponding to entries for ListPreference.
     * The default selection is resolved from {@link #defaultValue()}: first a
     * numeric match against entryValues, then an index into entryValues.
     */
    String[] entryValues() default {};
}

