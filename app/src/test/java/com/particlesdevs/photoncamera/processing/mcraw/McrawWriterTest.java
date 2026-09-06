package com.particlesdevs.photoncamera.processing.mcraw;

import org.junit.Test;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class McrawWriterTest {
    private int count(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("MOTION ", new String(bytes,0,7,java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(3,bytes[7]);
        b.position(bytes.length-24);
        assertEquals(0,b.getInt()); assertEquals(16,b.getInt());
        assertEquals(0x8A905612,b.getInt());
        int count = b.getInt();
        long index = b.getLong();
        assertEquals(bytes.length-24,index+(long)count*16);
        for (int i = 0; i < count; ++i) {
            b.position((int)index+i*16);
            int offset = Math.toIntExact(b.getLong());
            long timestamp = b.getLong();
            assertTrue(timestamp > 0);
            assertEquals(2,b.getInt(offset));
        }
        return count;
    }

    @Test public void emptyAndRepeatedCloseAreValid() throws Exception {
        Path file = Files.createTempFile("empty", ".mcraw");
        try {
            FileOutputStream stream = new FileOutputStream(file.toFile());
            McrawWriter writer = new McrawWriter(stream.getChannel(),stream,"{}");
            writer.close(); writer.close();
            assertEquals(0,count(file));
            assertThrows(IOException.class,() -> writer.writeFrame(ByteBuffer.allocate(1),1,"{}"));
        } finally { Files.deleteIfExists(file); }
    }

    @Test public void failedFrameIsRolledBackAndEarlierFramesSurvive() throws Exception {
        Path file = Files.createTempFile("rollback", ".mcraw");
        try {
            FileOutputStream stream = new FileOutputStream(file.toFile());
            McrawWriter writer = new McrawWriter(stream.getChannel(),stream,"{}");
            writer.writeFrame(ByteBuffer.wrap(new byte[]{1,2,3}),1,"{}");
            long committed = writer.bytesWritten();
            // Failure after the frame payload was written, before metadata can be committed.
            assertThrows(NullPointerException.class,() -> writer.writeFrame(ByteBuffer.allocate(20),2,null));
            assertEquals(committed,stream.getChannel().size());
            writer.writeFrame(ByteBuffer.wrap(new byte[]{4}),3,"{}");
            assertThrows(IOException.class,() -> writer.writeFrame(ByteBuffer.allocate(1),3,"{}"));
            writer.close();
            assertEquals(2,count(file));
        } finally { Files.deleteIfExists(file); }
    }

    @Test public void stopWhileWritingDrainsBeforeClosingAndRejectsLateFrames() throws Exception {
        Path file = Files.createTempFile("stop", ".mcraw");
        CountDownLatch entered = new CountDownLatch(1), unblock = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        RecordingWorker worker = new RecordingWorker();
        try {
            FileOutputStream stream = new FileOutputStream(file.toFile());
            McrawWriter writer = new McrawWriter(stream.getChannel(),stream,"{}");
            worker.submit(() -> {
                try {
                    entered.countDown();
                    assertTrue(unblock.await(5,TimeUnit.SECONDS));
                    writer.writeFrame(ByteBuffer.allocate(10),1,"{}");
                } catch (Throwable e) { failure.set(e); }
            });
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            worker.submit(() -> {
                try { writer.writeFrame(ByteBuffer.allocate(10),2,"{}"); }
                catch (Throwable e) { failure.set(e); }
            });
            worker.finish(() -> {
                try { writer.close(); } catch (Throwable e) { failure.set(e); }
                finally { done.countDown(); }
            });
            worker.finish(() -> failure.set(new AssertionError("Duplicate finalization")));
            assertFalse(worker.submit(() -> failure.set(new AssertionError("Late frame accepted"))));
            assertEquals(1,done.getCount());
            unblock.countDown();
            assertTrue(done.await(5,TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(2,count(file));
        } finally {
            unblock.countDown(); worker.finish(() -> {}); Files.deleteIfExists(file);
        }
    }
}
