package com.particlesdevs.photoncamera.processing;

import com.particlesdevs.photoncamera.util.FileManager;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ImagePath {
    public static String generateNewFileName(String prefix) {
        return prefix + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }



    public static Path newDNGFilePath() {
        return getNewImageFilePath("dng");
    }

    public static Path newImageFilePath() {
        return getNewImageFilePath("");
    }

    public static Path getNewImageFilePath(String extension) {
        File dir = FileManager.sDCIM_CAMERA;
        if (extension.equalsIgnoreCase("dng")) {
            dir = FileManager.sPHOTON_RAW_DIR;
        }
        if(!extension.isEmpty()) {
            return Paths.get(dir.getAbsolutePath(), generateNewFileName("IMG") + '.' + extension);
        } else {
            return Paths.get(dir.getAbsolutePath(), generateNewFileName("IMG"));
        }
    }

    public static Path getNewImageFolderPath() {
        File dir = FileManager.sPHOTON_RAW_DIR;
        return Paths.get(dir.getAbsolutePath(), generateNewFileName("IMG"));
    }

    public static Path getNewVideoFolderPath() {
        File dir = FileManager.sPHOTON_RAW_DIR;
        return Paths.get(dir.getAbsolutePath(), generateNewFileName("VID"));
    }
}
