package dev.hamid.redisj.persistence;

import dev.hamid.redisj.command.Command;
import dev.hamid.redisj.command.CommandContext;
import dev.hamid.redisj.command.CommandRegistry;
import dev.hamid.redisj.command.KeySpec;
import dev.hamid.redisj.command.ServerState;
import dev.hamid.redisj.command.WriteLog;
import dev.hamid.redisj.command.handlers.StreamCommands;
import dev.hamid.redisj.protocol.RespValue;
import dev.hamid.redisj.store.Database;
import dev.hamid.redisj.store.Entry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Durability for the dataset, in one of two modes:
 *
 * <ul>
 *   <li><b>{@code appendonly yes}:</b> every successful write is appended to {@value #AOF_FILE}.
 *       Each write is also followed by {@code PEXPIREAT} for any key it leaves with a TTL, so that
 *       expiry stays absolute across restarts. The file is replayed at startup. The fsync policy
 *       is everysec.</li>
 *   <li><b>{@code appendonly no}:</b> the dataset is written only by {@code SAVE}, to
 *       {@value #SNAPSHOT_FILE}, and loaded at startup.</li>
 * </ul>
 *
 * <p>{@code SAVE} in AOF mode rewrites the append-only file from the current dataset. Writes are
 * recorded only after they succeed, so the file never contains a failed command.
 *
 * <p>Used only from the execution loop, except {@link #close()}, which the server calls after it has
 * stopped.
 */
public final class Persistence implements WriteLog, AutoCloseable {

    /** Append-only file name, relative to the data directory. */
    public static final String AOF_FILE = "appendonly.aof";

    /** Snapshot file name, relative to the data directory. */
    public static final String SNAPSHOT_FILE = "dump.redisj";

    private static final System.Logger LOG = System.getLogger("redisj.persistence");

    private final ServerState state;
    private final boolean appendOnly;
    private final Path aofPath;
    private final Path snapshotPath;
    private AppendOnlyFile aof;
    private int streamDb;

    private Persistence(ServerState state, boolean appendOnly, Path aofPath, Path snapshotPath,
                        AppendOnlyFile aof, int streamDb) {
        this.state = state;
        this.appendOnly = appendOnly;
        this.aofPath = aofPath;
        this.snapshotPath = snapshotPath;
        this.aof = aof;
        this.streamDb = streamDb;
    }

    /**
     * Loads the persisted dataset, registers {@code SAVE}, and starts recording writes.
     *
     * @param state      the server state to load into
     * @param registry   the registry used to replay commands
     * @param dir        the data directory, created if missing
     * @param appendOnly {@code true} for AOF mode
     * @return the persistence handle; call {@link #close()} at shutdown
     * @throws IOException if a file is unreadable or corrupt
     */
    public static Persistence open(ServerState state, CommandRegistry registry, Path dir, boolean appendOnly)
            throws IOException {
        Files.createDirectories(dir);
        Path aofPath = dir.resolve(AOF_FILE);
        Path snapshotPath = dir.resolve(SNAPSHOT_FILE);
        Persistence persistence;
        state.setReplaying(true);
        Replayer.Result result;
        try {
            result = Replayer.replay(appendOnly ? aofPath : snapshotPath, registry);
        } finally {
            state.setReplaying(false);
        }
        if (appendOnly) {
            persistence = new Persistence(state, true, aofPath, snapshotPath,
                    AppendOnlyFile.open(aofPath), result.lastDb());
        } else {
            persistence = new Persistence(state, false, aofPath, snapshotPath, null, 0);
        }
        PersistenceCommands.register(registry, persistence);
        state.attachWriteLog(persistence);
        return persistence;
    }

    /**
     * Records a successful write in the append-only file. Does nothing in snapshot mode.
     *
     * @throws UncheckedIOException if the append fails; the command has already run, so the
     *                              server reports an error for it
     */
    @Override
    public void recordWrite(CommandContext ctx, Command command, RespValue reply) {
        if (!appendOnly) {
            return;
        }
        if (aof == null) {
            throw new UncheckedIOException(new IOException("append-only file is closed"));
        }
        try {
            int db = ctx.client().db();
            selectDb(db);
            List<byte[]> argv = new ArrayList<>(ctx.args().items());
            if (command.name().equals("XADD") && reply instanceof RespValue.BulkString id) {
                // Log the ID that was actually assigned, so replay does not generate another.
                argv.set(StreamCommands.idIndex(ctx.args()), id.bytes());
            }
            aof.append(ArgvCodec.encode(argv));
            Database database = ctx.db();
            for (String key : KeySpec.keysOf(command, ctx.args())) {
                Entry entry = database.get(key);
                if (entry != null && entry.hasExpiry()) {
                    aof.append(ArgvCodec.encode(List.of(
                            "PEXPIREAT".getBytes(StandardCharsets.US_ASCII),
                            key.getBytes(StandardCharsets.ISO_8859_1),
                            Long.toString(entry.expireAt()).getBytes(StandardCharsets.US_ASCII))));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("append-only file write failed", e);
        }
    }

    /**
     * Records the eviction of a key as {@code DEL}, so that replay removes the same key.
     *
     * @param db  the database the key was in
     * @param key the evicted key
     */
    @Override
    public void recordEviction(int db, String key) {
        if (!appendOnly || aof == null) {
            return;
        }
        try {
            selectDb(db);
            aof.append(ArgvCodec.encode(List.of("DEL".getBytes(StandardCharsets.US_ASCII),
                    key.getBytes(StandardCharsets.ISO_8859_1))));
        } catch (IOException e) {
            throw new UncheckedIOException("append-only file write failed", e);
        }
    }

    /** Writes a SELECT to the append-only file if the stream is not already in database {@code db}. */
    private void selectDb(int db) throws IOException {
        if (db != streamDb) {
            aof.append(ArgvCodec.encodeText("SELECT", Integer.toString(db)));
            streamDb = db;
        }
    }

    /**
     * Fsyncs the append-only file if the everysec interval has passed. Failures are logged.
     */
    @Override
    public void tick() {
        if (aof == null) {
            return;
        }
        try {
            aof.fsyncIfDue(System.nanoTime());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "append-only file fsync failed", e);
        }
    }

    /**
     * Writes the dataset: a snapshot file in snapshot mode, or a rewritten append-only file in AOF
     * mode. The new file is written first and then renamed into place, so a crash leaves the old
     * file intact.
     *
     * @throws IOException if the write or rename fails
     */
    public void save() throws IOException {
        Path target = appendOnly ? aofPath : snapshotPath;
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        SnapshotWriter.write(state, temporary);
        if (appendOnly) {
            closeAof();
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (appendOnly) {
                aof = AppendOnlyFile.open(aofPath);
                streamDb = -1;
            }
        }
    }

    /**
     * Flushes and closes the append-only file. Idempotent.
     */
    @Override
    public void close() {
        try {
            closeAof();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "append-only file close failed", e);
        }
    }

    private void closeAof() throws IOException {
        if (aof != null) {
            AppendOnlyFile file = aof;
            aof = null;
            file.close();
        }
    }
}
