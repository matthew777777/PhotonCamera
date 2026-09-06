package com.particlesdevs.photoncamera.processing.mcraw;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serializes admission with stop, keeping the finalizer behind every accepted frame. */
public final class RecordingWorker {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean accepting = true;

    public synchronized boolean submit(Runnable frame) {
        if (!accepting) return false;
        executor.execute(frame);
        return true;
    }

    public synchronized void finish(Runnable finalizer) {
        if (!accepting) return;
        accepting = false;
        try { executor.execute(finalizer); }
        finally { executor.shutdown(); }
    }
}
