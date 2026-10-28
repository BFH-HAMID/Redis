package dev.hamid.redisj.persistence;

import dev.hamid.redisj.command.ServerState;
import dev.hamid.redisj.store.Database;
import dev.hamid.redisj.store.Entry;
import dev.hamid.redisj.store.Stream;
import dev.hamid.redisj.store.StreamId;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes the whole dataset as a sequence of commands that rebuild it: {@code SELECT} per database,
 * then {@code SET}, {@code RPUSH}, {@code HSET} or {@code SADD} per key, followed by
 * {@code PEXPIREAT} for keys with a TTL.
 *
 * <p>This is a RedisJ snapshot format, not Redis's binary RDB format. It uses the same encoding and
 * replay path as the append-only file.
 */
public final class SnapshotWriter {

    private SnapshotWriter() {
    }

    /**
     * Writes the dataset to a file. The caller is responsible for making the write atomic.
     *
     * @param state  the server state to snapshot
     * @param target the file to create or overwrite
     * @throws IOException if writing fails
     */
    public static void write(ServerState state, Path target) throws IOException {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
            for (int index = 0; index < state.databases().count(); index++) {
                Database db = state.databases().get(index);
                List<String> keys = db.liveKeys();
                if (keys.isEmpty()) {
                    continue;
                }
                out.write(ArgvCodec.encodeText("SELECT", Integer.toString(index)));
                for (String key : keys) {
                    Entry entry = db.get(key);
                    if (entry == null) {
                        continue;
                    }
                    writeValue(out, key, entry.value());
                    if (entry.hasExpiry()) {
                        out.write(ArgvCodec.encode(argv("PEXPIREAT", key,
                                Long.toString(entry.expireAt()))));
                    }
                }
            }
        }
    }

    private static void writeValue(OutputStream out, String key, Object value) throws IOException {
        if (value instanceof byte[] bytes) {
            out.write(ArgvCodec.encode(List.of(text("SET"), text(key), bytes)));
        } else if (value instanceof LinkedList<?> list) {
            List<byte[]> argv = new ArrayList<>();
            argv.add(text("RPUSH"));
            argv.add(text(key));
            for (Object item : list) {
                argv.add((byte[]) item);
            }
            out.write(ArgvCodec.encode(argv));
        } else if (value instanceof LinkedHashMap<?, ?> map) {
            List<byte[]> argv = new ArrayList<>();
            argv.add(text("HSET"));
            argv.add(text(key));
            for (Map.Entry<?, ?> field : map.entrySet()) {
                argv.add(text((String) field.getKey()));
                argv.add((byte[]) field.getValue());
            }
            out.write(ArgvCodec.encode(argv));
        } else if (value instanceof LinkedHashSet<?> set) {
            List<byte[]> argv = new ArrayList<>();
            argv.add(text("SADD"));
            argv.add(text(key));
            for (Object member : (Set<?>) set) {
                argv.add(text((String) member));
            }
            out.write(ArgvCodec.encode(argv));
        } else if (value instanceof Stream stream) {
            writeStream(out, key, stream);
        } else {
            throw new IOException("cannot snapshot value of type " + value.getClass().getName());
        }
    }

    /**
     * Writes each entry as an explicit XADD. If the last ID is newer than the newest entry, which
     * happens after deletes or trims, a marker entry is added and removed again so the stream keeps
     * its last ID.
     */
    private static void writeStream(OutputStream out, String key, Stream stream) throws IOException {
        StreamId top = StreamId.MIN;
        for (Map.Entry<StreamId, List<byte[]>> entry : stream.entries().entrySet()) {
            List<byte[]> argv = new ArrayList<>();
            argv.add(text("XADD"));
            argv.add(text(key));
            argv.add(text(entry.getKey().toString()));
            argv.addAll(entry.getValue());
            out.write(ArgvCodec.encode(argv));
            top = entry.getKey();
        }
        StreamId last = stream.lastId();
        if (last.compareTo(top) > 0) {
            out.write(ArgvCodec.encode(List.of(text("XADD"), text(key), text(last.toString()),
                    text("_"), text("_"))));
            out.write(ArgvCodec.encode(List.of(text("XDEL"), text(key), text(last.toString()))));
        }
    }

    private static List<byte[]> argv(String... parts) {
        List<byte[]> out = new ArrayList<>(parts.length);
        for (String part : parts) {
            out.add(text(part));
        }
        return out;
    }

    /** Keys and fields are ISO-8859-1 strings; this maps them back to the original bytes. */
    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }
}
