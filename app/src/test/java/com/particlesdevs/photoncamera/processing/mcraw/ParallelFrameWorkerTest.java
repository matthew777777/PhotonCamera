package com.particlesdevs.photoncamera.processing.mcraw;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ParallelFrameWorkerTest {
    @Test public void parallelEncodingRetainsOrderAndStopDrainsBothStages() throws Exception {
        ParallelFrameWorker<Integer> worker = new ParallelFrameWorker<>(2,2);
        CountDownLatch secondEncoded = new CountDownLatch(1), allowFirst = new CountDownLatch(1), done = new CountDownLatch(1);
        List<Integer> written = new ArrayList<>();
        AtomicInteger released = new AtomicInteger(), errors = new AtomicInteger();
        try {
            assertTrue(worker.submit(() -> { assertTrue(allowFirst.await(5,TimeUnit.SECONDS)); return 1; },
                    written::add, v -> released.incrementAndGet(), e -> errors.incrementAndGet()));
            assertTrue(worker.submit(() -> { secondEncoded.countDown(); return 2; },
                    written::add, v -> released.incrementAndGet(), e -> errors.incrementAndGet()));
            assertTrue(secondEncoded.await(5,TimeUnit.SECONDS)); // Proves the encoders overlap.
            // Frame 2 has finished encoding, so its camera-buffer slot is available even though
            // ordered writing is still waiting for frame 1.
            assertTrue(worker.submit(() -> 3,written::add,v -> {},e -> {}));
            worker.finish(done::countDown);
            worker.finish(() -> errors.incrementAndGet());
            assertFalse(worker.submit(() -> 4,written::add,v -> {},e -> {}));
            assertEquals(1,done.getCount());
            allowFirst.countDown();
            assertTrue(done.await(5,TimeUnit.SECONDS));
            assertEquals(java.util.Arrays.asList(1,2,3),written);
            assertEquals(2,released.get()); assertEquals(0,errors.get());
        } finally { allowFirst.countDown(); worker.finish(done::countDown); }
    }

    @Test public void failedEncodingAndWritingStillReleaseCapacityAndFinalize() throws Exception {
        ParallelFrameWorker<Integer> worker = new ParallelFrameWorker<>(2,3);
        AtomicInteger released = new AtomicInteger(), errors = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        worker.submit(() -> { throw new IllegalStateException("encode"); },v -> {},
                v -> released.incrementAndGet(),e -> errors.incrementAndGet());
        worker.submit(() -> 2,v -> { throw new IllegalStateException("write"); },
                v -> released.incrementAndGet(),e -> errors.incrementAndGet());
        worker.submit(() -> 3,v -> {},v -> released.incrementAndGet(),e -> errors.incrementAndGet());
        worker.finish(done::countDown);
        assertTrue(done.await(5,TimeUnit.SECONDS));
        assertEquals(2,errors.get()); assertEquals(2,released.get());
    }
}
