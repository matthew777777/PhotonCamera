package com.particlesdevs.photoncamera.util;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.documentfile.provider.DocumentFile;

import com.anggrayudi.storage.file.DocumentFileCompat;
import com.anggrayudi.storage.file.DocumentFileType;
import com.anggrayudi.storage.file.DocumentFileUtils;
import com.anggrayudi.storage.file.StorageId;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Locale;

public class Log {
    private static final String PHOTON_LOG_SUBFOLDER = "PhotonLog";

    private static java.io.File logDir = null;
    private static Context logContext = null; // Application context for SimpleStorage
    private static final int LOG_RETENTION_DAYS = 10;
    private static String currentLogFileName = null;
    private static boolean logEnabled = true;

    // Thread-safe date formatters
    private static final ThreadLocal<SimpleDateFormat> dateFormatter =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd", Locale.US));
    private static final ThreadLocal<SimpleDateFormat> timeFormatter =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US));

    // Async logging
    private static HandlerThread logThread;
    private static Handler logHandler;
    private static BufferedWriter bufferedWriter = null;
    private static String currentDate = null;
    private static final int BUFFER_FLUSH_INTERVAL = 1000; // Flush every 1 second

    static {
        initLogThread();
    }

    private static void initLogThread() {
        logThread = new HandlerThread("LogWriterThread");
        logThread.start();
        logHandler = new Handler(logThread.getLooper());

        // Schedule periodic flush
        schedulePeriodicFlush();
    }

    private static void schedulePeriodicFlush() {
        logHandler.postDelayed(() -> {
            flushBuffer();
            schedulePeriodicFlush();
        }, BUFFER_FLUSH_INTERVAL);
    }

    /**
     * Use SimpleStorage to write logs to DCIM/PhotonCamera/PhotonLog.
     * Call this when the app has SAF storage access (e.g. from SplashActivity).
     */
    public static void setLogFolder(Context context) {
        if (context != null) {
            logContext = context.getApplicationContext();
            logDir = null;
            logHandler.post(() -> cleanupOldLogs());
        } else {
            logContext = null;
            closeWriter();
        }
    }

    /** @deprecated Prefer {@link #setLogFolder(Context)} with SimpleStorage. Kept for fallback. */
    @Deprecated
    public static void setLogFile(java.io.File folder) {
        if (folder != null && folder.isDirectory()) {
            logDir = folder;
            logContext = null;
            logHandler.post(() -> cleanupOldLogs());
        } else {
            logDir = null;
            closeWriter();
        }
    }

    /** Returns PhotonCamera/PhotonLog folder via SimpleStorage, or null if no access. */
    private static DocumentFile getLogFolderDocumentFile() {
        if (logContext == null || !SimpleStorageHelper.hasStorageAccess(logContext)) {
            return null;
        }
        DocumentFile photonCamera = DocumentFileCompat.fromSimplePath(
                logContext,
                StorageId.PRIMARY,
                SimpleStorageHelper.PHOTON_CAMERA_RELATIVE_PATH,
                DocumentFileType.FOLDER,
                true);
        if (photonCamera == null || !photonCamera.exists()) {
            return null;
        }
        DocumentFile logFolder = photonCamera.findFile(PHOTON_LOG_SUBFOLDER);
        if (logFolder != null && logFolder.exists()) {
            return logFolder;
        }
        logFolder = photonCamera.createDirectory(PHOTON_LOG_SUBFOLDER);
        return logFolder;
    }

    private static DocumentFile getLogFileDocumentFile() {
        DocumentFile folder = getLogFolderDocumentFile();
        if (folder == null || !folder.isDirectory()) return null;
        String today = dateFormatter.get().format(new java.util.Date());
        if (currentDate == null || !currentDate.equals(today)) {
            currentDate = today;
            currentLogFileName = "log-" + today + ".txt";
            closeWriter();
        }
        DocumentFile file = folder.findFile(currentLogFileName);
        if (file != null && file.exists()) {
            return file;
        }
        file = folder.createFile("text/plain", currentLogFileName);
        return file;
    }

    private static java.io.File getLogFile() {
        if (logDir == null) return null;
        String today = dateFormatter.get().format(new java.util.Date());

        if (currentDate == null || !currentDate.equals(today)) {
            currentDate = today;
            currentLogFileName = "log-" + today + ".txt";
            closeWriter();
        }

        return new java.io.File(logDir, currentLogFileName);
    }

    private static void cleanupOldLogs() {
        if (logContext != null) {
            DocumentFile folder = getLogFolderDocumentFile();
            if (folder != null && folder.isDirectory()) {
                DocumentFile[] files = folder.listFiles();
                if (files != null) {
                    long now = System.currentTimeMillis();
                    long retentionMillis = LOG_RETENTION_DAYS * 24L * 60L * 60L * 1000L;
                    for (DocumentFile file : files) {
                        if (file != null && file.isFile()) {
                            String name = file.getName();
                            if (name != null && name.startsWith("log-") && name.endsWith(".txt")) {
                                long lastModified = file.lastModified();
                                if (lastModified > 0 && now - lastModified > retentionMillis) {
                                    file.delete();
                                }
                            }
                        }
                    }
                }
            }
            return;
        }
        if (logDir == null) return;
        java.io.File[] files = logDir.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        long retentionMillis = LOG_RETENTION_DAYS * 24L * 60L * 60L * 1000L;
        for (java.io.File file : files) {
            if (file.isFile() && file.getName().startsWith("log-") && file.getName().endsWith(".txt")) {
                long lastModified = file.lastModified();
                if (now - lastModified > retentionMillis) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        }
    }

    private static void closeWriter() {
        if (bufferedWriter != null) {
            try {
                bufferedWriter.close();
            } catch (Exception e) {
                // Ignore
            }
            bufferedWriter = null;
        }
    }

    private static void flushBuffer() {
        if (bufferedWriter != null) {
            try {
                bufferedWriter.flush();
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private static void writeToFile(String level, String tag, String message) {
        boolean useSimpleStorage = (logContext != null && SimpleStorageHelper.hasStorageAccess(logContext));
        if (!logEnabled) return;
        if (!useSimpleStorage && logDir == null) return;

        long timestamp = System.currentTimeMillis();

        logHandler.post(() -> {
            try {
                if (useSimpleStorage) {
                    DocumentFile file = getLogFileDocumentFile();
                    if (file == null || !file.exists()) return;
                    if (bufferedWriter == null) {
                        java.io.OutputStream os = DocumentFileUtils.openOutputStream(file, logContext, true);
                        if (os == null) return;
                        bufferedWriter = new BufferedWriter(new OutputStreamWriter(os), 8192);
                    }
                } else {
                    java.io.File file = getLogFile();
                    if (file == null) return;
                    if (bufferedWriter == null) {
                        bufferedWriter = new BufferedWriter(new FileWriter(file, true), 8192);
                    }
                }
                if (bufferedWriter == null) return;

                String time = timeFormatter.get().format(new java.util.Date(timestamp));
                String logEntry = time + " " + level + "/" + tag + ": " + message + "\n";
                bufferedWriter.write(logEntry);
            } catch (Exception e) {
                closeWriter();
            }
        });
    }

    public static void d(String tag, String message) {
        if(!logEnabled) return;
        android.util.Log.d(tag, message);
        writeToFile("D", tag, message);
    }

    public static void w(String tag, String message) {
        if(!logEnabled) return;
        android.util.Log.w(tag, message);
        writeToFile("W", tag, message);
    }
    
    public static void w(String tag, String message, Throwable tr) {
        if(!logEnabled) return;
        android.util.Log.w(tag, message, tr);
        writeToFile("W", tag, message + "\n" + android.util.Log.getStackTraceString(tr));
    }

    public static void e(String tag, String message) {
        if(!logEnabled) return;
        android.util.Log.e(tag, message);
        writeToFile("E", tag, message);
    }
    
    public static void e(String tag, String message, Throwable tr) {
        if(!logEnabled) return;
        android.util.Log.e(tag, message, tr);
        writeToFile("E", tag, message + "\n" + android.util.Log.getStackTraceString(tr));
    }

    public static void i(String tag, String message) {
        if(!logEnabled) return;
        android.util.Log.i(tag, message);
        writeToFile("I", tag, message);
    }

    public static void v(String tag, String s) {
        if(!logEnabled) return;
        android.util.Log.v(tag, s);
        writeToFile("V", tag, s);
    }

    public static String getStackTraceString(Exception e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.toString()).append("\n");
        for (StackTraceElement element : e.getStackTrace()) {
            sb.append("\tat ").append(element).append("\n");
        }
        String stackTrace = sb.toString();
        e.printStackTrace();
        writeToFile("E", "Exception", stackTrace);
        return stackTrace;
    }
    
    public static void setLogEnabled(boolean enabled) {
        logEnabled = enabled;
    }

    /**
     * Reads relevant debug metrics from the stored log file for a specific capture.
     * @param startTime The epoch timestamp when capture processing started.
     */
    public static String getRelevantLogsSince(long startTime) {
        if (logContext == null) {
            logContext = com.particlesdevs.photoncamera.app.PhotonCamera.getContext();
        }
        if (logContext == null) return "";
        
        DocumentFile folder = getLogFolderDocumentFile();
        if (folder == null || !folder.isDirectory()) return "";

        String today = dateFormatter.get().format(new java.util.Date(startTime));
        String logFileName = "log-" + today + ".txt";
        DocumentFile file = folder.findFile(logFileName);
        if (file == null || !file.exists()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n--- REFERENCE DATA (From Storage Log) ---\n");

        try (java.io.InputStream is = DocumentFileUtils.openInputStream(file, logContext);
             java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() < 23) continue;

                try {
                    String timeStr = line.substring(0, 23);
                    java.util.Date parsedDate = timeFormatter.get().parse(timeStr);
                    if (parsedDate == null) continue;
                    long entryTime = parsedDate.getTime();

                    // Filter: within 60 seconds before and 20 seconds after capture start
                    if (entryTime < startTime - 60000 || entryTime > startTime + 20000) continue;

                    // Quick tag check before expensive substring/trim
                    int slashIdx = line.indexOf('/', 23);
                    int colonIdx = line.indexOf(':', slashIdx);
                    if (slashIdx == -1 || colonIdx == -1) continue;

                    String tag = line.substring(slashIdx + 1, colonIdx).trim();
                    boolean tagPossiblyRelevant = false;
                    char firstChar = Character.toLowerCase(tag.charAt(0));
                    if (firstChar == 'i' || firstChar == 'h' || firstChar == 'p' || 
                        firstChar == 'c' || firstChar == 'b' || firstChar == 'g' || 
                        firstChar == 'd' || firstChar == 'a') {
                        tagPossiblyRelevant = true;
                    }

                    if (!tagPossiblyRelevant) continue;

                    String message = line.substring(colonIdx + 1).trim();

                    boolean relevant = false;
                    String tagLower = tag.toLowerCase(Locale.US);
                    if (tagLower.contains("isoexposelector")) {
                        if (message.contains("Dynamic AE Factor") || message.contains("ShutterPriorityCurve") || message.contains("IsoSelected")) {
                            relevant = true;
                        }
                    } else if (tagLower.contains("hdrxprocessor")) {
                        if (message.contains("unlucky map") || message.contains("Removing unlucky") || message.contains("ApplyHdrX") || message.contains("Alignment elapsed")) {
                            relevant = true;
                        }
                    } else if (tagLower.contains("postpipeline")) {
                        if (message.contains("Building Experimental") || message.contains("Building Default") || message.contains("NoiseS") || message.contains("noise_o")) {
                            relevant = true;
                        }
                    } else if (tagLower.contains("capturecontroller") || tagLower.contains("burstcounter")) {
                        if (message.contains("CaptureStarted") || message.contains("CaptureCompleted") || message.contains("SequenceCompleted") || message.contains("Temperature") || message.contains("Focus")) {
                            relevant = true;
                        }
                    } else if (tagLower.contains("gyro")) {
                        if (message.contains("Shakiness") || message.contains("DelayUs")) {
                            relevant = true;
                        }
                    } else if (tagLower.contains("pyramidmerging") || tagLower.contains("dynamicnoise") || tagLower.contains("alignment") || tagLower.contains("pyramidalignment")) {
                        if (message.contains("Alignment time") || message.contains("Hot pixels") || message.contains("Fit S") || message.contains("Blended noise") || message.contains("Fitted noise") || message.contains("best exposure")) {
                            relevant = true;
                        }
                    } else if (tagLower.contains("imagesaver")) {
                        if (message.contains("notifyImageSavedStatus")) {
                            relevant = true;
                        }
                    }

                    if (relevant) {
                        sb.append("[").append(tag).append("] ").append(message).append("\n");
                    }
                } catch (Exception e) {
                    // Ignore line parse errors
                }
            }
        } catch (Exception e) {
            sb.append("Error reading log file: ").append(e.getMessage()).append("\n");
        }

        if (sb.length() < 45) { // If only header exists (\n--- REFERENCE DATA (From Storage Log) ---\n is 43 chars)
            return "";
        }

        return sb.toString();
    }
    
    // Cleanup method to call when app is closing
    public static void shutdown() {
        if (logHandler != null) {
            logHandler.post(() -> {
                flushBuffer();
                closeWriter();
            });
            logThread.quitSafely();
        }
    }
}
