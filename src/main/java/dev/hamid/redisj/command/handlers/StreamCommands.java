package dev.hamid.redisj.command.handlers;

import dev.hamid.redisj.command.Args;
import dev.hamid.redisj.command.Command;
import dev.hamid.redisj.command.CommandContext;
import dev.hamid.redisj.command.CommandException;
import dev.hamid.redisj.command.CommandRegistry;
import dev.hamid.redisj.command.Replies;
import dev.hamid.redisj.protocol.RespValue;
import dev.hamid.redisj.store.Database;
import dev.hamid.redisj.store.Entry;
import dev.hamid.redisj.store.Stream;
import dev.hamid.redisj.store.StreamId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stream commands: XADD, XLEN, XRANGE, XREVRANGE, XDEL, XTRIM and XREAD.
 *
 * <p>XREAD is non-blocking. {@code BLOCK} is rejected with an error. Auto-generated IDs
 * ({@code *} and {@code ms-*}) are rewritten to explicit IDs in the append-only file; see
 * {@link #idIndex(Args)}.
 */
public final class StreamCommands {

    private static final Set<Command.Flag> WRITE = Set.of(Command.Flag.WRITE);
    private static final Set<Command.Flag> READ = Set.of(Command.Flag.READONLY);

    private StreamCommands() {
    }

    /**
     * Registers the commands.
     *
     * @param registry the registry to add to
     */
    public static void register(CommandRegistry registry) {
        registry.register(Command.of("XADD", -5, WRITE, 1, 1, 1, StreamCommands::xadd));
        registry.register(Command.of("XLEN", 2, READ, 1, 1, 1, StreamCommands::xlen));
        registry.register(Command.of("XRANGE", -4, READ, 1, 1, 1, ctx -> range(ctx, false)));
        registry.register(Command.of("XREVRANGE", -4, READ, 1, 1, 1, ctx -> range(ctx, true)));
        registry.register(Command.of("XDEL", -3, WRITE, 1, 1, 1, StreamCommands::xdel));
        registry.register(Command.of("XTRIM", -4, WRITE, 1, 1, 1, StreamCommands::xtrim));
        registry.register(Command.of("XREAD", -4, READ, 0, 0, 0, StreamCommands::xread));
    }

    /**
     * Finds the position of the ID argument in an XADD call, after any {@code MAXLEN} option. The
     * append-only file uses this to replace an auto-generated ID with the one actually used.
     *
     * @param args a call to XADD that has already succeeded
     * @return the index of the ID argument
     */
    public static int idIndex(Args args) {
        int i = 2;
        while (i < args.size() && "MAXLEN".equals(args.upper(i))) {
            i++;
            if (i < args.size() && isApprox(args.text(i))) {
                i++;
            }
            i++;
        }
        return i;
    }

    static RespValue xadd(CommandContext ctx) {
        Args a = ctx.args();
        String key = a.key(1);
        long maxLen = maxLenOption(a);
        int idIndex = idIndex(a);
        int fieldStart = idIndex + 1;
        int fieldCount = a.size() - fieldStart;
        if (idIndex >= a.size() || fieldCount < 2 || fieldCount % 2 != 0) {
            throw wrongArgs("xadd");
        }
        Database db = ctx.db();
        Stream stream = streamOf(db.get(key));
        StreamId last = stream == null ? StreamId.MIN : stream.lastId();
        StreamId id = nextId(a.text(idIndex), last, db.now());
        List<byte[]> fields = new ArrayList<>(fieldCount);
        for (int j = fieldStart; j < a.size(); j++) {
            fields.add(a.bytes(j));
        }
        if (stream == null) {
            stream = new Stream();
            db.put(key, stream, false);
        }
        stream.append(id, fields);
        if (maxLen >= 0) {
            stream.trimToLength(maxLen);
        }
        return Replies.bulkText(id.toString());
    }

    static RespValue xlen(CommandContext ctx) {
        Stream stream = streamOf(ctx.db().get(ctx.args().key(1)));
        return Replies.integer(stream == null ? 0 : stream.size());
    }

    static RespValue range(CommandContext ctx, boolean reverse) {
        Args a = ctx.args();
        String key = a.key(1);
        String first = a.text(2);
        String second = a.text(3);
        // XRANGE takes start then end; XREVRANGE takes end then start.
        StreamId lo;
        StreamId hi;
        if (reverse) {
            hi = parseBound(first, false);
            lo = parseBound(second, true);
        } else {
            lo = parseBound(first, true);
            hi = parseBound(second, false);
        }
        long count = countOption(a, 4);
        Stream stream = streamOf(ctx.db().get(key));
        if (stream == null) {
            return Replies.array(List.of());
        }
        return entriesReply(stream.range(lo, hi, count, reverse));
    }

    static RespValue xdel(CommandContext ctx) {
        Args a = ctx.args();
        Stream stream = streamOf(ctx.db().get(a.key(1)));
        List<StreamId> ids = new ArrayList<>(a.size() - 2);
        for (int i = 2; i < a.size(); i++) {
            ids.add(parseId(a.text(i)));
        }
        return Replies.integer(stream == null ? 0 : stream.delete(ids));
    }

    static RespValue xtrim(CommandContext ctx) {
        Args a = ctx.args();
        String key = a.key(1);
        if (!"MAXLEN".equals(a.upper(2))) {
            throw CommandException.syntax();
        }
        int i = 3;
        if (i < a.size() && isApprox(a.text(i))) {
            i++;
        }
        if (i + 1 != a.size()) {
            throw CommandException.syntax();
        }
        long maxLen = a.longValue(i);
        if (maxLen < 0) {
            throw new CommandException("ERR The MAXLEN argument must be >= 0.");
        }
        Stream stream = streamOf(ctx.db().get(key));
        return Replies.integer(stream == null ? 0 : stream.trimToLength(maxLen));
    }

    static RespValue xread(CommandContext ctx) {
        Args a = ctx.args();
        long count = -1;
        int i = 1;
        while (i < a.size()) {
            String option = a.upper(i);
            if (option.equals("COUNT")) {
                if (i + 1 >= a.size()) {
                    throw CommandException.syntax();
                }
                count = positiveCount(a.longValue(i + 1));
                i += 2;
            } else if (option.equals("BLOCK")) {
                throw new CommandException("ERR XREAD BLOCK is not supported yet");
            } else if (option.equals("STREAMS")) {
                break;
            } else {
                throw CommandException.syntax();
            }
        }
        if (i >= a.size()) {
            throw CommandException.syntax();
        }
        int first = i + 1;
        int remaining = a.size() - first;
        if (remaining == 0 || remaining % 2 != 0) {
            throw new CommandException("ERR Unbalanced 'xread' list of streams: for each stream key an"
                    + " ID or '$' must be specified.");
        }
        int streams = remaining / 2;
        Database db = ctx.db();
        List<RespValue> results = new ArrayList<>();
        for (int k = 0; k < streams; k++) {
            String key = a.key(first + k);
            String idText = a.text(first + streams + k);
            Stream stream = streamOf(db.get(key));
            StreamId after;
            if (idText.equals("$")) {
                after = stream == null ? StreamId.MIN : stream.lastId();
            } else {
                after = parseBound(idText, true);
            }
            List<Map.Entry<StreamId, List<byte[]>>> found =
                    stream == null ? List.of() : stream.after(after, count);
            if (!found.isEmpty()) {
                results.add(new RespValue.Array(List.of(bulkKey(key), entriesReply(found))));
            }
        }
        return results.isEmpty() ? new RespValue.Null() : Replies.array(results);
    }

    // ------------------------------------------------------------------ helpers

    private static Stream streamOf(Entry entry) {
        if (entry == null) {
            return null;
        }
        if (entry.value() instanceof Stream stream) {
            return stream;
        }
        throw CommandException.wrongType();
    }

    /** Parses the MAXLEN option of XADD, or returns -1 when absent. */
    private static long maxLenOption(Args a) {
        long maxLen = -1;
        int i = 2;
        while (i < a.size() && "MAXLEN".equals(a.upper(i))) {
            i++;
            if (i < a.size() && isApprox(a.text(i))) {
                i++;
            }
            if (i >= a.size()) {
                throw CommandException.syntax();
            }
            maxLen = a.longValue(i);
            if (maxLen < 0) {
                throw new CommandException("ERR The MAXLEN argument must be >= 0.");
            }
            i++;
        }
        return maxLen;
    }

    /** Chooses the ID for a new entry: {@code *}, {@code ms-*}, or an explicit {@code ms-seq}. */
    private static StreamId nextId(String text, StreamId last, long now) {
        if (text.equals("*")) {
            if (now > last.ms()) {
                return new StreamId(now, 0);
            }
            return new StreamId(last.ms(), last.seq() + 1);
        }
        if (text.endsWith("-*")) {
            long ms = unsigned(text.substring(0, text.length() - 2));
            if (ms > last.ms()) {
                return new StreamId(ms, 0);
            }
            if (ms == last.ms()) {
                return new StreamId(ms, last.seq() + 1);
            }
            throw topItemError();
        }
        StreamId id = parseId(text);
        if (id.equals(StreamId.MIN)) {
            throw new CommandException("ERR The ID specified in XADD must be greater than 0-0");
        }
        if (id.compareTo(last) <= 0) {
            throw topItemError();
        }
        return id;
    }

    private static CommandException topItemError() {
        return new CommandException("ERR The ID specified in XADD is equal or smaller than the target"
                + " stream top item");
    }

    /** Parses an exact {@code ms-seq} ID. */
    private static StreamId parseId(String text) {
        int dash = text.indexOf('-');
        if (dash <= 0 || dash == text.length() - 1) {
            throw invalidId();
        }
        return new StreamId(unsigned(text.substring(0, dash)), unsigned(text.substring(dash + 1)));
    }

    /**
     * Parses a range bound. {@code -} and {@code +} mean the smallest and largest IDs. A bare
     * timestamp means {@code ms-0} as a start and {@code ms-max} as an end.
     */
    private static StreamId parseBound(String text, boolean start) {
        if (text.equals("-")) {
            return StreamId.MIN;
        }
        if (text.equals("+")) {
            return StreamId.MAX;
        }
        if (text.indexOf('-') > 0) {
            return parseId(text);
        }
        long ms = unsigned(text);
        return start ? new StreamId(ms, 0) : new StreamId(ms, Long.MAX_VALUE);
    }

    private static long unsigned(String text) {
        try {
            long value = Long.parseLong(text);
            if (value < 0) {
                throw invalidId();
            }
            return value;
        } catch (NumberFormatException e) {
            throw invalidId();
        }
    }

    /** Reads an optional {@code COUNT n} option starting at {@code from}, or returns -1. */
    private static long countOption(Args a, int from) {
        long count = -1;
        int i = from;
        while (i < a.size()) {
            if (!"COUNT".equals(a.upper(i)) || i + 1 >= a.size()) {
                throw CommandException.syntax();
            }
            count = positiveCount(a.longValue(i + 1));
            i += 2;
        }
        return count;
    }

    private static long positiveCount(long count) {
        if (count < 1) {
            throw new CommandException("ERR COUNT must be > 0");
        }
        return count;
    }

    private static boolean isApprox(String text) {
        return text.equals("~") || text.equals("=");
    }

    private static RespValue entriesReply(List<Map.Entry<StreamId, List<byte[]>>> entries) {
        List<RespValue> out = new ArrayList<>(entries.size());
        for (Map.Entry<StreamId, List<byte[]>> entry : entries) {
            List<RespValue> fields = new ArrayList<>(entry.getValue().size());
            for (byte[] bytes : entry.getValue()) {
                fields.add(Replies.bulk(bytes));
            }
            out.add(new RespValue.Array(List.of(Replies.bulkText(entry.getKey().toString()),
                    new RespValue.Array(fields))));
        }
        return Replies.array(out);
    }

    private static RespValue bulkKey(String key) {
        return Replies.bulk(key.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static CommandException invalidId() {
        return new CommandException("ERR Invalid stream ID specified as stream command argument");
    }

    private static CommandException wrongArgs(String command) {
        return new CommandException("ERR wrong number of arguments for '" + command + "' command");
    }
}
