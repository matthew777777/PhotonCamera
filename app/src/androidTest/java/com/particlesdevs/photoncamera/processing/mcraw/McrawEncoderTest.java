package com.particlesdevs.photoncamera.processing.mcraw;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class McrawEncoderTest {
    @Test public void encodeAndStopRepeatedlyOnDevice() throws Exception {
        File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getExternalFilesDir(null),"mcraw-test.mcraw");
        for (int session = 0; session < 5; ++session) {
            FileOutputStream stream = new FileOutputStream(file);
            McrawWriter writer = new McrawWriter(stream.getChannel(),stream,
                    "{\"extraData\":{\"audioSampleRate\":44100,\"audioChannels\":0}}");
            RecordingWorker worker = new RecordingWorker();
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            for (int frame = 0; frame < 2; ++frame) {
                final long timestamp = 1_000_000_000L+frame*33_333_333L;
                assertTrue(worker.submit(() -> {
                    try {
                        ByteBuffer raw = ByteBuffer.allocateDirect(64*8*2);
                        while (raw.hasRemaining()) raw.put((byte)255);
                        raw.flip();
                        ByteBuffer encoded = ByteBuffer.allocateDirect(8192);
                        int bytes = McrawEncoder.encode(raw,raw.remaining(),64,8,128,false,0,8,false,encoded);
                        assertTrue(bytes > 16);
                        encoded.limit(bytes);
                        writer.writeFrame(encoded,timestamp,"{\"width\":64,\"height\":8,\"compressionType\":7}");
                    } catch (Throwable e) { error.set(e); }
                }));
            }
            worker.finish(() -> {
                try { writer.close(); } catch (Throwable e) { error.set(e); }
                finally { done.countDown(); }
            });
            assertFalse(worker.submit(() -> fail("Late frame")));
            assertTrue(done.await(15,TimeUnit.SECONDS));
            assertNull(error.get());
            byte[] bytes = Files.readAllBytes(file.toPath());
            ByteBuffer footer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(0x8A905612,footer.getInt(bytes.length-16));
            assertEquals(2,footer.getInt(bytes.length-12));
        }
    }

    @Test public void rejectsShortInputAndSupportsPackedRaw10() throws Exception {
        ByteBuffer raw = ByteBuffer.allocateDirect(64*8/4*5);
        ByteBuffer encoded = ByteBuffer.allocateDirect(8192);
        while (raw.hasRemaining()) raw.put((byte)255);
        raw.flip();
        assertTrue(McrawEncoder.encode(raw,raw.remaining(),64,8,80,true,0,8,false,encoded) > 16);
        try {
            McrawEncoder.encode(raw,10,64,8,80,true,0,8,false,encoded);
            fail("Accepted short plane");
        } catch (IOException expected) { /* JNI must throw, not crash native code. */ }
    }
}
