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
 * Replays a file of RESP-encoded commands through the command registry, which rebuilds the
 * dataset at startup.
 *
 * <p>An incomplete record at the end of the file, such as one cut short by a crash, is discarded
 * and the file is truncated to the last complete record. A reply that is an error stops the replay,
 * because the file then no longer matches the dataset.
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

    private Replayer() {
    }

    /**
     * @param file     the file to replay; a missing file replays nothing
     * @param registry the registry that applies each command
     * @return what was replayed
     * @throws IOException if the file is unreadable, corrupt, or a command fails on replay
     */
    public static Result replay(Path file, CommandRegistry registry) throws IOException {
        if (!Files.exists(file)) {
            return new Result(0, 0);
        }
        byte[] bytes = Files.readAllBytes(file);
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
                throw new IOException("corrupt record in " + file + " at byte " + start + ": "
                        + e.getMessage(), e);
            }
            if (value == null) {
                break;
            }
            goodEnd = buffer.position();
            if (!(value instanceof RespValue.Array)) {
                throw new IOException("unexpected non-array record in " + file + " at byte " + start);
            }
            RespValue reply = registry.handle(client, value);
            if (reply instanceof RespValue.Error error) {
                throw new IOException("replay failed in " + file + " at byte " + start + ": "
                        + error.message());
            }
            records++;
        }
        if (goodEnd < bytes.length) {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.truncate(goodEnd);
            }
        }
        return new Result(records, client.db());
    }
}
