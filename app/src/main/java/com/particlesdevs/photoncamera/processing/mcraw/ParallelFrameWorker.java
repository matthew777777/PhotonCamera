package com.particlesdevs.photoncamera.processing.mcraw;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/** Bounded parallel encoding followed by ordered writing. Stop drains both stages. */
public final class ParallelFrameWorker<T> {
    private final ExecutorService encoders;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final Semaphore slots;
    private boolean accepting = true;

    public ParallelFrameWorker(int threads, int capacity) {
        if (threads < 1 || capacity < threads) throw new IllegalArgumentException("Invalid pipeline capacity");
        encoders = Executors.newFixedThreadPool(threads);
        slots = new Semaphore(capacity);
    }

    public synchronized boolean isReady() {
        return accepting && slots.availablePermits() > 0;
    }

    /** False means ownership of the input remains with the caller. */
    public synchronized boolean submit(Callable<T> encode, Consumer<T> write,
                                        Consumer<T> release, Consumer<Throwable> error) {
        if (!accepting) return false;
        // Never block the ImageReader callback. Blocking it fills the HAL queue and produces one
        // long timestamp gap when encoding falls behind. A rejected frame is closed by the caller,
        // allowing subsequent camera frames to retain their real capture cadence.
        if (!slots.tryAcquire()) return false;
        Future<T> encoded;
        try {
            encoded = encoders.submit(() -> {
                try { return encode.call(); }
                finally { slots.release(); }
            });
        } catch (RuntimeException e) {
            slots.release();
            throw e;
        }
        writer.execute(() -> {
            T frame = null;
            try {
                frame = encoded.get();
                write.accept(frame);
            } catch (ExecutionException e) {
                error.accept(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error.accept(e);
            } catch (Throwable e) {
                error.accept(e);
            } finally {
                if (frame != null) release.accept(frame);
            }
        });
        return true;
    }

    public synchronized void finish(Runnable finalizer) {
        if (!accepting) return;
        accepting = false;
        encoders.shutdown();
        try { writer.execute(finalizer); }
        finally { writer.shutdown(); }
    }
}
