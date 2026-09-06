package com.particlesdevs.photoncamera.processing.processor;

import android.graphics.ImageFormat;
import android.graphics.Point;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.media.Image;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;

import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.control.Gyro;
import com.particlesdevs.photoncamera.processing.ImageSaver;
import com.particlesdevs.photoncamera.processing.ProcessingEventsListener;
import com.particlesdevs.photoncamera.processing.mcraw.McrawEncoder;
import com.particlesdevs.photoncamera.processing.mcraw.McrawMetadata;
import com.particlesdevs.photoncamera.processing.mcraw.McrawWriter;
import com.particlesdevs.photoncamera.processing.mcraw.ParallelFrameWorker;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.FlacAudioRecorder;
import com.particlesdevs.photoncamera.util.Log;
import com.particlesdevs.photoncamera.util.SimpleStorageHelper;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Semaphore;

public class RawVideoProcessor extends ProcessorBase {
    private static final String TAG = "RawVideoProcessor";
    // The tested camera HAL exposes five RAW buffers. Leave one available to the producer while
    // three workers consume at most four queued Images.
    private static final int ENCODER_THREADS = 2;
    private static final int PIPELINE_CAPACITY = 4;
    // pending includes both camera Images in the encoder stage and encoded buffers awaiting disk.
    private static final int TOTAL_BUFFER_CAPACITY = PIPELINE_CAPACITY * 2;
    public static volatile int videoCounter;
    // Accessed under this processor's monitor. Workers only reference their own session.
    private Session current;

    public static class RawVideoStats {
        public final int bufferedFrames, bufferCapacity;
        public final long elapsedMs, estimatedBytes, availableBytes;
        public RawVideoStats(int bufferedFrames, int bufferCapacity, long elapsedMs,
                             long estimatedBytes, long availableBytes) {
            this.bufferedFrames = bufferedFrames;
            this.bufferCapacity = bufferCapacity;
            this.elapsedMs = elapsedMs;
            this.estimatedBytes = estimatedBytes;
            this.availableBytes = availableBytes;
        }
    }

    private static final class EncodedFrame {
        final ByteBuffer data;
        final long timestamp;
        final long receivedTimestampMs;
        final String metadata;
        EncodedFrame(ByteBuffer data, long timestamp, long receivedTimestampMs, String metadata) {
            this.data = data; this.timestamp = timestamp;
            this.receivedTimestampMs = receivedTimestampMs; this.metadata = metadata;
        }
    }
    private static final class AudioChunk {
        final long timestampNs; final short[] samples;
        AudioChunk(long timestampNs,short[] samples) { this.timestampNs=timestampNs; this.samples=samples; }
    }

    private static final class Session {
        final Path folder;
        final ParallelFrameWorker<EncodedFrame> pipeline =
                new ParallelFrameWorker<>(ENCODER_THREADS,PIPELINE_CAPACITY);
        final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();
        final FlacAudioRecorder audio = new FlacAudioRecorder();
        final ArrayList<AudioChunk> audioChunks = new ArrayList<>();
        final AtomicInteger pending = new AtomicInteger();
        final AtomicInteger saved = new AtomicInteger();
        final long startMs = System.currentTimeMillis();
        final boolean bin = PreferenceKeys.isRawVideoDownscale4x();
        final boolean crop = PreferenceKeys.isRawVideoCrop169();
        final boolean topCrop = ImageSaver.SETTINGS.cropType;
        final CameraCharacteristics characteristics;
        final CaptureResult firstResult;
        final CaptureRequest request;
        final int rotation;
        final double fps;
        volatile boolean failed;
        final TreeMap<Long, CaptureResult> results = new TreeMap<>();
        boolean stopped;
        volatile long bytes;
        final AtomicLong encodeNs = new AtomicLong();
        long writeNs, callbackNs, firstFrameNs, lastFrameNs, lastStatsMs;
        long firstSavedNs, lastSavedNs;
        volatile long monotonicToCameraOffsetNs = Long.MIN_VALUE;
        int received;
        int dropped;
        long available;
        Parameters parameters;
        final ArrayBlockingQueue<ByteBuffer> freeEncoded =
                new ArrayBlockingQueue<>(PIPELINE_CAPACITY);
        final Semaphore encodedSlots = new Semaphore(PIPELINE_CAPACITY);
        int encodedCapacity, width, height, stride, top, cropHeight, outWidth, outHeight;
        boolean raw10;
        FileOutputStream output;
        volatile McrawWriter container;
        Session(Path folder, CameraCharacteristics characteristics, CaptureResult result,
                CaptureRequest request, int rotation, double fps) {
            this.folder = folder; this.characteristics = characteristics; this.firstResult = result;
            this.request = request; this.rotation = rotation; this.fps = fps;
        }
    }

    public RawVideoProcessor(ProcessingEventsListener listener) { super(listener); }

    public synchronized void videoStart(Path outputPath, ParseExif.ExifData exif,
                                        CameraCharacteristics characteristics, CaptureResult result,
                                        CaptureRequest request, int rotation, ProcessingCallback callback) {
        if (current != null) videoEnd();
        Path destination = outputPath;
        try {
            Files.createDirectories(outputPath.getParent());
            // Names have second precision. Reserve a distinct filename for rapid restarts.
            for (int suffix = 1; ; suffix++) {
                if (!Files.exists(destination)) break;
                String name = outputPath.getFileName().toString();
                destination = outputPath.resolveSibling(name.substring(0,name.length()-6)
                        + "_" + suffix + ".mcraw");
            }
        } catch (IOException e) {
            reportError("Cannot start RAW video",e);
            return;
        }
        Session s = new Session(destination,characteristics,result,request,rotation,resolveFrameRate());
        try {
            // Opening a new file through Android storage can take hundreds of milliseconds.
            // Do it before admitting camera frames so that storage setup cannot fill the RAW
            // pipeline and stall the camera at the beginning of a recording.
            int fd = SimpleStorageHelper.openFdForWrite(destination.toString());
            s.output = fd >= 0
                    ? new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(fd))
                    : new FileOutputStream(destination.toFile());
        } catch (IOException e) {
            reportError("Cannot create MediaCinemaRAW output",e);
            return;
        }
        try { s.available = new StatFs(destination.getParent().toString()).getAvailableBytes(); }
        catch (Exception e) { Log.w(TAG,"Storage statistics unavailable: " + e); }
        current = s;
        videoCounter = 0;
        s.audioExecutor.execute(() -> {
            try {
                if (!s.audio.start((timestamp,samples,channels) -> {
                    synchronized (s.audioChunks) {
                        if (s.container == null) s.audioChunks.add(new AudioChunk(timestamp,samples));
                        else try { s.container.writeAudio(samples,toCameraTime(s,timestamp)); }
                        catch (IOException e) { s.failed=true; reportError("Embedded audio write failed",e); }
                    }
                }))
                    Log.w(TAG,"RAW video microphone unavailable");
            } catch (Exception e) { Log.e(TAG,"RAW audio start failed: " + e); }
        });
        try {
            PhotonCamera.getGyro().startVideoRecording(s.folder,s.fps);
        }
        catch (Exception e) { Log.e(TAG,"Gyro recording unavailable: " + e); }
    }

    private void initialize(Session s, Image image) throws IOException {
        int format = image.getFormat();
        if (format != ImageFormat.RAW_SENSOR && format != ImageFormat.RAW10)
            throw new IOException("Unsupported RAW video format: " + format);
        s.raw10 = format == ImageFormat.RAW10;
        s.width = image.getWidth(); s.height = image.getHeight();
        s.stride = image.getPlanes()[0].getRowStride();
        if (!s.raw10 && image.getPlanes()[0].getPixelStride() != 2)
            throw new IOException("Unsupported RAW16 pixel stride");
        int alignment = s.bin ? 8 : 4;
        s.cropHeight = s.crop ? Math.min(s.height,s.width*9/16) : s.height;
        // The supplied decoder always emits four rows at a time.
        s.cropHeight -= s.cropHeight % alignment;
        s.top = s.crop && !s.topCrop ? (s.height-s.cropHeight)/2 : 0;
        s.top &= ~1; // Retain CFA phase.
        s.outWidth = s.bin ? s.width/2 : s.width;
        s.outHeight = s.bin ? s.cropHeight/2 : s.cropHeight;
        if (s.outHeight <= 0 || (s.outWidth & 1) != 0)
            throw new IOException("Unsupported RAW video dimensions");
        long pixels = (long)((s.outWidth+63)/64*64)*s.outHeight;
        s.encodedCapacity = Math.toIntExact(pixels*2+pixels/8+4096);
        s.parameters = new Parameters();
        s.parameters.rawSize = new Point(s.width,s.height);
        s.parameters.FillConstParameters(s.characteristics,s.parameters.rawSize);
        s.parameters.FillDynamicParameters(s.firstResult,s.request,100);
        s.parameters.cameraRotation = s.rotation;
        PhotonCamera.getGyro().syncFirstFrame(image.getTimestamp());
    }

    /** The Image is always closed, including late callbacks after stop and failed submissions. */
    public synchronized void videoCycle(Image image) {
        Session s = current;
        boolean transferred = false;
        long callbackStart = System.nanoTime();
        try {
            if (s == null || s.failed) return;
            if (s.received++ == 0) {
                s.firstFrameNs = image.getTimestamp();
                s.monotonicToCameraOffsetNs = image.getTimestamp()-System.nanoTime();
            }
            s.lastFrameNs = image.getTimestamp();
            if (s.parameters == null) initialize(s,image);
            if (image.getWidth() != s.width || image.getHeight() != s.height
                    || image.getPlanes()[0].getRowStride() != s.stride)
                throw new IOException("RAW geometry changed during recording");
            videoCounter++;
            final long timestamp = image.getTimestamp();
            final long receivedTimestampMs = System.currentTimeMillis();
            s.pending.incrementAndGet();
            transferred = s.pipeline.submit(() -> encodeFrame(s,image,timestamp,receivedTimestampMs),
                    frame -> writeFrame(s,frame),
                    frame -> {
                        s.freeEncoded.offer(frame.data);
                        s.encodedSlots.release();
                        s.pending.decrementAndGet();
                    },
                    error -> {
                        s.failed = true;
                        s.pending.decrementAndGet();
                        reportError("RAW video encoding failed",new IOException(error));
                    });
            if (!transferred) { s.pending.decrementAndGet(); s.dropped++; }
        } catch (Exception e) {
            if (s != null) s.failed = true;
            reportError("RAW video recording failed",e);
        } finally {
            if (!transferred) image.close();
            if (s != null) {
                s.callbackNs += System.nanoTime()-callbackStart;
                long now = System.currentTimeMillis();
                if (now-s.lastStatsMs >= 100) {
                    s.lastStatsMs = now;
                    processingEventsListener.onProcessingChanged(new RawVideoStats(
                            Math.min(s.pending.get(),TOTAL_BUFFER_CAPACITY),TOTAL_BUFFER_CAPACITY,
                            now-s.startMs,s.bytes,s.available));
                }
            }
        }
    }

    public synchronized void videoCaptureResult(CaptureResult result) {
        Session s = current;
        Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
        if (s == null || timestamp == null) return;
        synchronized (s.results) {
            s.results.put(timestamp,result);
            while (s.results.size() > 128) s.results.pollFirstEntry();
            s.results.notifyAll();
        }
    }

    private CaptureResult frameResult(Session s, long timestamp) {
        synchronized (s.results) {
            CaptureResult result = s.results.remove(timestamp);
            return result == null ? s.firstResult : result;
        }
    }

    private EncodedFrame encodeFrame(Session s, Image image, long timestamp,
                                     long receivedTimestampMs) throws Exception {
        ByteBuffer encoded = null;
        boolean slot = false;
        try {
            s.encodedSlots.acquire();
            slot = true;
            encoded = s.freeEncoded.poll();
            if (encoded == null) encoded = ByteBuffer.allocateDirect(s.encodedCapacity);
            long start = System.nanoTime();
            ByteBuffer raw = image.getPlanes()[0].getBuffer().duplicate();
            int length = McrawEncoder.encode(raw,raw.remaining(),s.width,s.height,s.stride,
                    s.raw10,s.top,s.cropHeight,s.bin,encoded);
            s.encodeNs.addAndGet(System.nanoTime()-start);
            encoded.clear(); encoded.limit(length);
        } catch (Exception e) {
            if (encoded != null) s.freeEncoded.offer(encoded);
            if (slot) s.encodedSlots.release();
            throw e;
        } catch (Error e) {
            if (encoded != null) s.freeEncoded.offer(encoded);
            if (slot) s.encodedSlots.release();
            throw e;
        } finally { image.close(); }
        try {
            return new EncodedFrame(encoded,timestamp,receivedTimestampMs,null);
        } catch (Exception e) {
            s.freeEncoded.offer(encoded);
            throw e;
        }
    }

    private void writeFrame(Session s, EncodedFrame frame) {
        try {
            if (s.failed) return;
            if (s.container == null) {
                String metadata = McrawMetadata.container(s.parameters,s.fps,
                        s.audio.getSampleRate(),s.audio.getChannels());
                s.container = new McrawWriter(s.output.getChannel(),s.output,metadata);
                synchronized (s.audioChunks) {
                    for (AudioChunk chunk : s.audioChunks)
                        s.container.writeAudio(chunk.samples,toCameraTime(s,chunk.timestampNs));
                    s.audioChunks.clear();
                }
            }
            long start = System.nanoTime();
            String frameMetadata = McrawMetadata.frame(s.parameters,frameResult(s,frame.timestamp),
                    s.outWidth,s.outHeight,frame.timestamp,frame.receivedTimestampMs);
            s.container.writeFrame(frame.data,frame.timestamp,frameMetadata);
            s.writeNs += System.nanoTime()-start;
            s.bytes = s.container.bytesWritten();
            if (s.container.frameCount() == 1) s.firstSavedNs = frame.timestamp;
            s.lastSavedNs = frame.timestamp;
            s.saved.incrementAndGet();
        } catch (Exception e) {
            s.failed = true;
            reportError("RAW video write failed",e);
        }
    }

    public void videoEnd() { videoEnd(() -> {}); }

    public synchronized void videoEnd(Runnable finalized) {
        Session s = current;
        if (s == null) { finalized.run(); return; }
        current = null; // Stop admission before scheduling the finalizer.
        synchronized (s.results) { s.stopped = true; s.results.notifyAll(); }
        Gyro.MediaCinemaRawSamples gyro;
        try { gyro = PhotonCamera.getGyro().stopVideoRecordingForContainer(); }
        catch (Exception e) {
            Log.e(TAG,"Gyro stop failed: " + e);
            gyro = new Gyro.MediaCinemaRawSamples(new long[0],new float[0],new float[0],new float[0],0);
        }
        final Gyro.MediaCinemaRawSamples finalGyro = gyro;
        s.audioExecutor.execute(() -> {
            try { s.audio.stop(); }
            catch (Exception e) { reportError("RAW audio finalization failed",e); }
        });
        s.audioExecutor.shutdown();
        s.pipeline.finish(() -> {
            try {
                try {
                    if (!s.audioExecutor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS))
                        Log.w(TAG,"Timed out while stopping MediaCinemaRAW audio");
                }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (s.container != null) {
                    synchronized (s.audioChunks) {
                        for (AudioChunk chunk : s.audioChunks)
                            s.container.writeAudio(chunk.samples,toCameraTime(s,chunk.timestampNs));
                    }
                    if (s.monotonicToCameraOffsetNs != Long.MIN_VALUE)
                        for (int i=0;i<finalGyro.count;i++)
                            finalGyro.timestampsNs[i] += s.monotonicToCameraOffsetNs;
                    s.container.writeGyro(finalGyro.timestampsNs,finalGyro.x,finalGyro.y,finalGyro.z,finalGyro.count);
                    s.container.close();
                    s.output = null; // McrawWriter owns and closed it.
                } else if (s.output != null) {
                    s.output.close();
                    s.output = null;
                }
                int saved = s.container == null ? 0 : s.container.frameCount();
                Log.d(TAG,"RAW video finalized: " + s.folder + " saved=" + saved
                        + " dropped=" + s.dropped + " encodeMsPerFrame=" + s.encodeNs.get()/1e6/Math.max(1,saved)
                        + " writeMsPerFrame=" + s.writeNs/1e6/Math.max(1,saved)
                        + " savedFps=" + ((saved-1)*1e9/Math.max(1,s.lastSavedNs-s.firstSavedNs))
                        + " received=" + s.received
                        + " inputFps=" + ((s.received-1)*1e9/Math.max(1,s.lastFrameNs-s.firstFrameNs))
                        + " callbackMsPerFrame=" + s.callbackNs/1e6/Math.max(1,s.received));
            } catch (Exception e) { reportError("MediaCinemaRAW finalization failed",e); }
            finally {
                s.freeEncoded.clear();
                finalized.run();
            }
        });
        // The queued finalizer owns and closes the file after all accepted writes.
    }

    private void reportError(String message, Exception e) {
        Log.e(TAG,message + ": " + Log.getStackTraceString(e));
        PhotonCamera.getMainHandler().post(() ->
                processingEventsListener.onProcessingError(message + ": " + e.getMessage()));
    }

    private long toCameraTime(Session s,long monotonicTimestampNs) {
        long offset=s.monotonicToCameraOffsetNs;
        return offset==Long.MIN_VALUE ? monotonicTimestampNs : monotonicTimestampNs+offset;
    }

    private double resolveFrameRate() {
        switch (PreferenceKeys.getFpsMode()) {
            case 1: return 24.0;
            case 3: return 60.0;
            default: return 30.0;
        }
    }
}
