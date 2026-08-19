package com.particlesdevs.photoncamera.settings;

/**
 * Registry for classes that use @SensorConfig annotations.
 */
public final class SensorConfigRegistry {
    private SensorConfigRegistry() {}

    public static final Class<?>[] SENSOR_CONFIG_CLASSES = {
        com.particlesdevs.photoncamera.capture.CaptureController.class,
        com.particlesdevs.photoncamera.processing.render.Parameters.class
    };
}
