package dev.hamid.redisj.command.handlers;

import dev.hamid.redisj.command.Args;
import dev.hamid.redisj.command.Command;
import dev.hamid.redisj.command.CommandContext;
import dev.hamid.redisj.command.CommandException;
import dev.hamid.redisj.command.CommandRegistry;
import dev.hamid.redisj.command.Numbers;
import dev.hamid.redisj.command.Replies;
import dev.hamid.redisj.protocol.RespValue;
import dev.hamid.redisj.store.Database;
import dev.hamid.redisj.store.Entry;
import dev.hamid.redisj.store.GlobMatcher;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Key iteration: KEYS and SCAN.
 *
 * <p>SCAN uses a cursor over the 32-bit FNV-1a hash of each key. A call returns the keys whose hash
 * is at or above the cursor, in hash order, up to COUNT. It finishes any hash group it has started,
 * so the next cursor is the hash of the first key not yet returned, and 0 means the scan is done.
 * Keys present for the whole scan are returned exactly once. Keys that are added or removed during
 * the scan may or may not be returned, as Redis allows.
 *
 * <p><b>Known cost:</b> each SCAN call collects and sorts the live keys of the database, which is
 * O(n log n) per call instead of Redis's O(1) amortized per call. Scanning a large database
 * therefore costs more than in Redis.
 */
public final class ScanCommands {

    private static final long MAX_CURSOR = 0xFFFF_FFFFL;
    private static final long DEFAULT_COUNT = 10;

    private ScanCommands() {
    }

    /**
     * Registers the commands.
     *
     * @param registry the registry to add to
     */
    public static void register(CommandRegistry registry) {
        registry.register(Command.of("KEYS", 2, Set.of(Command.Flag.READONLY), 0, 0, 0, ScanCommands::keys));
        registry.register(Command.of("SCAN", -2, Set.of(Command.Flag.READONLY), 0, 0, 0, ScanCommands::scan));
    }

    static RespValue keys(CommandContext ctx) {
        String pattern = ctx.args().key(1);
        List<String> matched = new ArrayList<>();
        for (String key : ctx.db().liveKeys()) {
            if (GlobMatcher.matches(pattern, key)) {
                matched.add(key);
            }
        }
        return Replies.array(KeyCommands.keysAsBulk(matched));
    }

    static RespValue scan(CommandContext ctx) {
        Args args = ctx.args();
        long cursor = parseCursor(args.text(1));
        String pattern = null;
        String type = null;
        long count = DEFAULT_COUNT;
        for (int i = 2; i < args.size(); i++) {
            String option = args.upper(i);
            if (i + 1 >= args.size()) {
                throw CommandException.syntax();
            }
            switch (option) {
                case "MATCH" -> pattern = args.key(++i);
                case "COUNT" -> {
                    count = args.longValue(++i);
                    if (count < 1) {
                        throw CommandException.syntax();
                    }
                }
                case "TYPE" -> type = args.text(++i).toLowerCase(Locale.ROOT);
                default -> throw CommandException.syntax();
            }
        }
        Database db = ctx.db();
        List<Candidate> pending = new ArrayList<>();
        for (String key : db.liveKeys()) {
            long hash = fnv1a(key);
            if (hash >= cursor) {
                pending.add(new Candidate(key, hash));
            }
        }
        pending.sort(Comparator.comparingLong(Candidate::hash).thenComparing(Candidate::key));

        int end = (int) Math.min(count, pending.size());
        while (end > 0 && end < pending.size() && pending.get(end).hash() == pending.get(end - 1).hash()) {
            end++;
        }
        long next = end < pending.size() ? pending.get(end).hash() : 0;

        List<String> page = new ArrayList<>();
        for (int i = 0; i < end; i++) {
            String key = pending.get(i).key();
            if (pattern != null && !GlobMatcher.matches(pattern, key)) {
                continue;
            }
            if (type != null) {
                Entry entry = db.get(key);
                if (entry == null || !entry.typeName().equals(type)) {
                    continue;
                }
            }
            page.add(key);
        }
        return Replies.array(List.of(
                Replies.bulkText(Long.toString(next)),
                Replies.array(KeyCommands.keysAsBulk(page))));
    }

    /**
     * @param text the cursor argument
     * @return the cursor, in the range 0 to {@code 2^32 - 1}
     * @throws CommandException if the text is not a valid cursor
     */
    static long parseCursor(String text) {
        OptionalLong value = Numbers.tryParseLong(text.getBytes(StandardCharsets.UTF_8));
        if (value.isEmpty() || value.getAsLong() < 0 || value.getAsLong() > MAX_CURSOR) {
            throw new CommandException("ERR invalid cursor");
        }
        return value.getAsLong();
    }

    /**
     * 32-bit FNV-1a over the key bytes. Keys are ISO-8859-1 strings, so each char is one byte.
     *
     * @param key the key
     * @return the hash, as an unsigned value in {@code [0, 2^32)}
     */
    static long fnv1a(String key) {
        int hash = 0x811C9DC5;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i) & 0xFF;
            hash *= 0x01000193;
        }
        return hash & 0xFFFF_FFFFL;
    }

    /** A key considered by SCAN, with its hash. */
    private record Candidate(String key, long hash) {
    }
}
