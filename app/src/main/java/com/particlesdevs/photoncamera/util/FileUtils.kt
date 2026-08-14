package com.particlesdevs.photoncamera.util

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

/**
 * Modern Kotlin/Java replacement for legacy commons-io FileUtils.
 */
object FileUtils {

    /**
     * Gets the extension of a filename.
     */
    @JvmStatic
    fun getExtension(filename: String?): String {
        if (filename == null) return ""
        val index = filename.lastIndexOf('.')
        return if (index == -1) "" else filename.substring(index + 1)
    }

    /**
     * Converts a byte count to a human-readable display size.
     */
    @JvmStatic
    fun byteCountToDisplaySize(size: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        if (size <= 0) return "0 B"
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.ROOT, "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    /**
     * Replacement for FileUtils.copyFile using Java NIO.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun copyFile(src: File, dest: File) {
        Files.copy(src.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * Replacement for FileUtils.deleteDirectory using Kotlin extension.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun deleteDirectory(directory: File) {
        if (!directory.exists()) return
        if (!directory.deleteRecursively()) {
            throw IOException("Failed to delete directory: ${directory.absolutePath}")
        }
    }
}
