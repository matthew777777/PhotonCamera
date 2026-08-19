package com.particlesdevs.photoncamera.settings.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for automatically generating preference settings for per-sensor parameters.
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
     */
    float step() default 1.0f;
}
