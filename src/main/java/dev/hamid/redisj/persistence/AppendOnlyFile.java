package dev.hamid.redisj.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The append-only file: a sequence of RESP-encoded write commands.
 *
 * <p>Records are written at once with a single write call. Data is fsynced at most once per second
 * (the {@code everysec} policy) by {@link #fsyncIfDue(long)}, and on close.
 */
public final class AppendOnlyFile implements AutoCloseable {

    private static final long FSYNC_INTERVAL_NANOS = 1_000_000_000L;

    private final FileChannel channel;
    private boolean dirty;
    private long lastFsyncNanos = System.nanoTime();

    private AppendOnlyFile(FileChannel channel) {
        this.channel = channel;
    }

    /**
     * Opens the file for appending, creating it if needed.
     *
     * @param path the file
     * @return the open file
     * @throws IOException if it cannot be opened
     */
    public static AppendOnlyFile open(Path path) throws IOException {
        return new AppendOnlyFile(FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND));
    }

    /**
     * @param record one encoded record
     * @throws IOException if the write fails
     */
    public void append(byte[] record) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(record);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        dirty = true;
    }

    /**
     * Fsyncs if there are unsynced records and at least one second has passed since the last fsync.
     *
     * @param nowNanos the current {@link System#nanoTime()}
     * @throws IOException if the fsync fails
     */
    public void fsyncIfDue(long nowNanos) throws IOException {
        if (dirty && nowNanos - lastFsyncNanos >= FSYNC_INTERVAL_NANOS) {
            sync(nowNanos);
        }
    }

    private void sync(long nowNanos) throws IOException {
        channel.force(false);
        dirty = false;
        lastFsyncNanos = nowNanos;
    }

    @Override
    public void close() throws IOException {
        try {
            if (dirty) {
                sync(System.nanoTime());
            }
        } finally {
            channel.close();
        }
    }
}
