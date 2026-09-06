package com.particlesdevs.photoncamera.processing.mcraw;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Single-owner, little-endian version 3 container writer. Close only after draining frames. */
public final class McrawWriter implements Closeable {
    private final FileChannel channel;
    private final Closeable owner;
    private final ArrayList<Long> offsets = new ArrayList<>();
    private final ArrayList<Long> timestamps = new ArrayList<>();
    private final ArrayList<Long> audioOffsets = new ArrayList<>();
    private final ArrayList<Long> audioTimestamps = new ArrayList<>();
    private final ArrayList<Long> gyroOffsets = new ArrayList<>();
    private final ArrayList<Long> gyroTimestamps = new ArrayList<>();
    private boolean closed;
    private long committedEnd;

    public McrawWriter(FileChannel channel, Closeable owner, String metadata) throws IOException {
        this.channel = channel;
        this.owner = owner;
        try {
            write(ByteBuffer.wrap(new byte[]{'M','O','T','I','O','N',' ',3}));
            json(metadata);
            committedEnd = channel.position();
        } catch (IOException | RuntimeException e) {
            try { owner.close(); } catch (IOException closeError) { e.addSuppressed(closeError); }
            throw e;
        }
    }

    private ByteBuffer numbers(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }
    private void write(ByteBuffer data) throws IOException {
        while (data.hasRemaining()) {
            int written = channel.write(data);
            if (written < 0) throw new IOException("Unexpected end of MediaCinemaRAW output");
            if (written == 0) Thread.yield();
        }
    }
    private void item(int type, int size) throws IOException {
        ByteBuffer b = numbers(8).putInt(type).putInt(size);
        b.flip(); write(b);
    }
    private void json(String metadata) throws IOException {
        byte[] data = metadata.getBytes(StandardCharsets.UTF_8);
        item(3,data.length); write(ByteBuffer.wrap(data));
    }

    public synchronized void writeFrame(ByteBuffer encoded, long timestamp, String metadata) throws IOException {
        if (closed) throw new IOException("MediaCinemaRAW writer is closed");
        if (!timestamps.isEmpty() && timestamp <= timestamps.get(timestamps.size()-1))
            throw new IOException("Non-increasing RAW frame timestamp");
        long offset = committedEnd;
        try {
            item(2,encoded.remaining()); write(encoded); json(metadata);
            committedEnd = channel.position();
            offsets.add(offset); timestamps.add(timestamp);
        } catch (IOException | RuntimeException e) {
            // A partial frame must never enter the index. Preserve earlier complete frames.
            try { channel.truncate(offset); channel.position(offset); }
            catch (IOException rollbackError) { e.addSuppressed(rollbackError); }
            throw e;
        }
    }

    public long bytesWritten() { return committedEnd; }
    public int frameCount() { return offsets.size(); }

    public synchronized void writeAudio(short[] samples, long timestampNs) throws IOException {
        long offset = channel.position();
        item(5,Math.multiplyExact(samples.length,2));
        ByteBuffer data = numbers(samples.length*2);
        for (short sample : samples) data.putShort(sample);
        data.flip(); write(data);
        item(6,8);
        ByteBuffer metadata = numbers(8).putLong(timestampNs);
        metadata.flip(); write(metadata);
        audioOffsets.add(offset); audioTimestamps.add(timestampNs);
        committedEnd = channel.position();
    }

    public synchronized void writeGyro(long[] timestampsNs, float[] x, float[] y, float[] z, int count)
            throws IOException {
        if (count <= 0) return;
        long offset = channel.position();
        item(9,Math.addExact(8,Math.multiplyExact(count,24)));
        ByteBuffer data = numbers(8+count*24).putInt(1).putInt(count);
        for (int i=0; i<count; i++)
            data.putLong(timestampsNs[i]).putFloat(x[i]).putFloat(y[i]).putFloat(z[i]).putInt(0);
        data.flip(); write(data);
        gyroOffsets.add(offset); gyroTimestamps.add(timestampsNs[0]);
        committedEnd = channel.position();
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            channel.truncate(committedEnd); channel.position(committedEnd);
            if (!audioOffsets.isEmpty()) {
                item(4,16+audioOffsets.size()*16);
                ByteBuffer index = numbers(16+audioOffsets.size()*16)
                        .putLong(audioOffsets.size()).putLong(audioTimestamps.get(0)/1_000_000L);
                for (int i=0;i<audioOffsets.size();i++)
                    index.putLong(audioOffsets.get(i)).putLong(audioTimestamps.get(i));
                index.flip(); write(index);
            }
            if (!gyroOffsets.isEmpty()) {
                item(8,8+gyroOffsets.size()*16);
                ByteBuffer index = numbers(8+gyroOffsets.size()*16).putInt(1).putInt(gyroOffsets.size());
                for (int i=0;i<gyroOffsets.size();i++)
                    index.putLong(gyroOffsets.get(i)).putLong(gyroTimestamps.get(i));
                index.flip(); write(index);
            }
            item(1,Math.multiplyExact(offsets.size(),16));
            long indexOffset = channel.position();
            ByteBuffer entries = numbers(16*256);
            for (int i = 0; i < offsets.size(); i++) {
                if (entries.remaining() < 16) { entries.flip(); write(entries); entries.clear(); }
                entries.putLong(offsets.get(i)).putLong(timestamps.get(i));
            }
            entries.flip(); write(entries);
            item(0,16);
            ByteBuffer footer = numbers(16).putInt(0x8A905612).putInt(offsets.size()).putLong(indexOffset);
            footer.flip(); write(footer);
            channel.force(true);
        } finally { owner.close(); }
    }
}
