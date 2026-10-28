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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hash commands: HSET, HGET, HDEL, HEXISTS, HLEN, HGETALL, HKEYS and HVALS.
 *
 * <p>A hash is a {@link LinkedHashMap} from field name to value. Field names are ISO-8859-1
 * strings, so they stay binary safe. A hash that becomes empty is removed from the keyspace.
 */
public final class HashCommands {

    private static final Set<Command.Flag> WRITE_FAST = Set.of(Command.Flag.WRITE, Command.Flag.FAST);
    private static final Set<Command.Flag> READ_FAST = Set.of(Command.Flag.READONLY, Command.Flag.FAST);

    private HashCommands() {
    }

    /**
     * Registers the commands.
     *
     * @param registry the registry to add to
     */
    public static void register(CommandRegistry registry) {
        registry.register(Command.of("HSET", -4, WRITE_FAST, 1, 1, 1, HashCommands::hset));
        registry.register(Command.of("HGET", 3, READ_FAST, 1, 1, 1, HashCommands::hget));
        registry.register(Command.of("HDEL", -3, WRITE_FAST, 1, 1, 1, HashCommands::hdel));
        registry.register(Command.of("HEXISTS", 3, READ_FAST, 1, 1, 1, HashCommands::hexists));
        registry.register(Command.of("HLEN", 2, READ_FAST, 1, 1, 1, HashCommands::hlen));
        registry.register(Command.of("HGETALL", 2, READ_FAST, 1, 1, 1, HashCommands::hgetall));
        registry.register(Command.of("HKEYS", 2, READ_FAST, 1, 1, 1, HashCommands::hkeys));
        registry.register(Command.of("HVALS", 2, READ_FAST, 1, 1, 1, HashCommands::hvals));
    }

    /**
     * @param entry the entry, possibly {@code null}
     * @return the hash, or {@code null} if the key is missing
     * @throws CommandException with WRONGTYPE if the entry is not a hash
     */
    static Map<String, byte[]> hashOf(Entry entry) {
        if (entry == null) {
            return null;
        }
        if (!(entry.value() instanceof LinkedHashMap<?, ?>)) {
            throw CommandException.wrongType();
        }
        @SuppressWarnings("unchecked")
        Map<String, byte[]> hash = (Map<String, byte[]>) entry.value();
        return hash;
    }

    private static Map<String, byte[]> hashForWrite(Database db, String key) {
        Entry entry = db.get(key);
        if (entry == null) {
            Map<String, byte[]> created = new LinkedHashMap<>();
            db.put(key, created, false);
            return created;
        }
        return hashOf(entry);
    }

    static RespValue hset(CommandContext ctx) {
        Args args = ctx.args();
        if (args.size() % 2 == 1) {
            throw new CommandException("ERR wrong number of arguments for 'hset' command");
        }
        Database db = ctx.db();
        Map<String, byte[]> hash = hashForWrite(db, args.key(1));
        long added = 0;
        for (int i = 2; i < args.size(); i += 2) {
            if (hash.put(args.key(i), args.bytes(i + 1)) == null) {
                added++;
            }
        }
        return Replies.integer(added);
    }

    static RespValue hget(CommandContext ctx) {
        Map<String, byte[]> hash = hashOf(ctx.db().get(ctx.args().key(1)));
        byte[] value = hash == null ? null : hash.get(ctx.args().key(2));
        return value == null ? Replies.nil() : Replies.bulk(value);
    }

    static RespValue hdel(CommandContext ctx) {
        Database db = ctx.db();
        String key = ctx.args().key(1);
        Map<String, byte[]> hash = hashOf(db.get(key));
        if (hash == null) {
            return Replies.integer(0);
        }
        long removed = 0;
        for (int i = 2; i < ctx.args().size(); i++) {
            if (hash.remove(ctx.args().key(i)) != null) {
                removed++;
            }
        }
        if (hash.isEmpty()) {
            db.remove(key);
        }
        return Replies.integer(removed);
    }

    static RespValue hexists(CommandContext ctx) {
        Map<String, byte[]> hash = hashOf(ctx.db().get(ctx.args().key(1)));
        boolean present = hash != null && hash.containsKey(ctx.args().key(2));
        return Replies.integer(present ? 1 : 0);
    }

    static RespValue hlen(CommandContext ctx) {
        Map<String, byte[]> hash = hashOf(ctx.db().get(ctx.args().key(1)));
        return Replies.integer(hash == null ? 0 : hash.size());
    }

    static RespValue hgetall(CommandContext ctx) {
        Map<String, byte[]> hash = hashOf(ctx.db().get(ctx.args().key(1)));
        List<RespValue> out = new ArrayList<>();
        if (hash != null) {
            for (Map.Entry<String, byte[]> field : hash.entrySet()) {
                out.add(Replies.bulk(field.getKey().getBytes(StandardCharsets.ISO_8859_1)));
                out.add(Replies.bulk(field.getValue()));
            }
        }
        return Replies.array(out);
    }

    static RespValue hkeys(CommandContext ctx) {
        Map<String, byte[]> hash = hashOf(ctx.db().get(ctx.args().key(1)));
        List<String> keys = hash == null ? List.of() : new ArrayList<>(hash.keySet());
        return Replies.array(KeyCommands.keysAsBulk(keys));
    }

    static RespValue hvals(CommandContext ctx) {
        Map<String, byte[]> hash = hashOf(ctx.db().get(ctx.args().key(1)));
        List<RespValue> out = new ArrayList<>();
        if (hash != null) {
            for (byte[] value : hash.values()) {
                out.add(Replies.bulk(value));
            }
        }
        return Replies.array(out);
    }
}
