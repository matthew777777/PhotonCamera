package com.particlesdevs.photoncamera.settings.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for automatically generating per-physical-sensor preference settings.
 * Works like {@link Tunable}, but preferences are stored per physical camera id
 * using keys of the form {@code pref_sensorconfig_<sensorId>_<fieldName>}.
 *
 * Usage example:
 * <pre>
 * {@code
 * @SensorConfig(
 *     title = "Black Level",
 *     description = "Override black level for this sensor (0 = auto)",
 *     min = 0.0f,
 *     max = 8192.0f,
 *     step = 1.0f
 * )
 * float[] blackLevel;
 * }
 * </pre>
 *
 * A {@code float[]} field is edited through a single scalar slider and the value
 * is applied to every element of the array.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SensorConfig {
    /**
     * Display title in settings UI
     */
    String title();

    /**
     * Optional description/summary for the setting
     */
    String description() default "";

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
     * If step is 0, the setting is edited as a plain text input without a slider
     */
    float step() default 1.0f;
}
