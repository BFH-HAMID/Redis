package dev.hamid.redisj.persistence;

import dev.hamid.redisj.command.CommandRegistry;
import dev.hamid.redisj.protocol.RespParser;
import dev.hamid.redisj.protocol.RespProtocolException;
import dev.hamid.redisj.protocol.RespValue;
import dev.hamid.redisj.server.ClientConnection;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Replays RESP-encoded commands through the command registry, which rebuilds the dataset at
 * startup or when a replica loads a master's snapshot.
 *
 * <p>For files, an incomplete record at the end, such as one cut short by a crash, is discarded and
 * the file is truncated to the last complete record. A reply that is an error stops the replay,
 * because the data then no longer matches the log.
 */
public final class Replayer {

    /**
     * Result of a replay.
     *
     * @param records number of commands applied
     * @param lastDb  the database the replayed client ended in, which is the database the file's
     *                next record must be relative to
     */
    public record Result(int records, int lastDb) {
    }

    /** Internal outcome, including how many bytes were complete records. */
    private record Outcome(Result result, int goodEnd) {
    }

    private Replayer() {
    }

    /**
     * Replays a file. A missing file replays nothing. A torn final record is removed from the file.
     *
     * @param file     the file to replay
     * @param registry the registry that applies each command
     * @return what was replayed
     * @throws IOException if the file is unreadable, corrupt, or a command fails on replay
     */
    public static Result replay(Path file, CommandRegistry registry) throws IOException {
        if (!Files.exists(file)) {
            return new Result(0, 0);
        }
        byte[] bytes = Files.readAllBytes(file);
        Outcome outcome = apply(bytes, registry, file.toString());
        if (outcome.goodEnd() < bytes.length) {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.truncate(outcome.goodEnd());
            }
        }
        return outcome.result();
    }

    /**
     * Replays an in-memory command stream, such as a snapshot received from a master. A torn final
     * record is an error here, because a transfer must be complete.
     *
     * @param bytes    the encoded commands
     * @param registry the registry that applies each command
     * @return what was replayed
     * @throws IOException if the stream is corrupt, incomplete, or a command fails
     */
    public static Result applyBytes(byte[] bytes, CommandRegistry registry) throws IOException {
        Outcome outcome = apply(bytes, registry, "snapshot");
        if (outcome.goodEnd() < bytes.length) {
            throw new IOException("snapshot is truncated");
        }
        return outcome.result();
    }

    private static Outcome apply(byte[] bytes, CommandRegistry registry, String source)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        RespParser parser = new RespParser();
        ClientConnection client = ClientConnection.detached(0);
        int records = 0;
        int goodEnd = 0;
        while (buffer.hasRemaining()) {
            int start = buffer.position();
            RespValue value;
            try {
                value = parser.parse(buffer);
            } catch (RespProtocolException e) {
                throw new IOException("corrupt record in " + source + " at byte " + start + ": "
                        + e.getMessage(), e);
            }
            if (value == null) {
                break;
            }
            goodEnd = buffer.position();
            if (!(value instanceof RespValue.Array)) {
                throw new IOException("unexpected non-array record in " + source + " at byte " + start);
            }
            RespValue reply = registry.handle(client, value);
            if (reply instanceof RespValue.Error error) {
                throw new IOException("replay failed in " + source + " at byte " + start + ": "
                        + error.message());
            }
            records++;
        }
        return new Outcome(new Result(records, client.db()), goodEnd);
    }
}
