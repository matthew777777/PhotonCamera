package com.particlesdevs.photoncamera.processing;

import com.particlesdevs.photoncamera.settings.annotations.Tunable;

public class ImageSaverSettings {

    @Tunable(
            title = "Crop Edge",
            category = "ImageSaver",
            description = "When enabled, crop from the edge (top) of the image instead of the center",
            defaultValue = 0, min = 0, max = 1, step = 1
    )
    public boolean cropType;

    @Tunable(
            title = "Ultra HDR JPEG",
            category = "ImageSaver",
            description = "Embed an Android Ultra HDR gain map on Android 14 and later; unsupported devices save a normal JPEG",
            defaultValue = 0, min = 0, max = 1, step = 1
    )
    public boolean ultraHdr;
}
