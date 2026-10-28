package dev.hamid.redisj.command.handlers;

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
import java.util.List;
import java.util.Set;

/**
 * Generic key commands: DEL, UNLINK, EXISTS, TYPE, RENAME, PERSIST, TTL, PTTL, DBSIZE, FLUSHDB
 * and FLUSHALL.
 */
public final class KeyCommands {

    private static final Set<Command.Flag> WRITE = Set.of(Command.Flag.WRITE);
    private static final Set<Command.Flag> READ = Set.of(Command.Flag.READONLY, Command.Flag.FAST);

    private KeyCommands() {
    }

    /**
     * Registers the commands.
     *
     * @param registry the registry to add to
     */
    public static void register(CommandRegistry registry) {
        registry.register(Command.of("DEL", -2, WRITE, 1, -1, 1, KeyCommands::del));
        registry.register(Command.of("UNLINK", -2, WRITE, 1, -1, 1, KeyCommands::del));
        registry.register(Command.of("EXISTS", -2, READ, 1, -1, 1, KeyCommands::exists));
        registry.register(Command.of("TYPE", 2, READ, 1, 1, 1, KeyCommands::type));
        registry.register(Command.of("RENAME", 3, WRITE, 1, 2, 1, KeyCommands::rename));
        registry.register(Command.of("PERSIST", 2, WRITE, 1, 1, 1, KeyCommands::persist));
        registry.register(Command.of("TTL", 2, READ, 1, 1, 1, ctx -> ttl(ctx, 1000)));
        registry.register(Command.of("PTTL", 2, READ, 1, 1, 1, ctx -> ttl(ctx, 1)));
        registry.register(Command.of("DBSIZE", 1, READ, 0, 0, 0, KeyCommands::dbsize));
        registry.register(Command.of("FLUSHDB", -1, WRITE, 0, 0, 0, KeyCommands::flushdb));
        registry.register(Command.of("FLUSHALL", -1, WRITE, 0, 0, 0, KeyCommands::flushall));
    }

    static RespValue del(CommandContext ctx) {
        Database db = ctx.db();
        long removed = 0;
        for (int i = 1; i < ctx.args().size(); i++) {
            if (db.remove(ctx.args().key(i))) {
                removed++;
            }
        }
        return Replies.integer(removed);
    }

    static RespValue exists(CommandContext ctx) {
        Database db = ctx.db();
        long count = 0;
        for (int i = 1; i < ctx.args().size(); i++) {
            if (db.get(ctx.args().key(i)) != null) {
                count++;
            }
        }
        return Replies.integer(count);
    }

    static RespValue type(CommandContext ctx) {
        Entry entry = ctx.db().get(ctx.args().key(1));
        return new RespValue.SimpleString(entry == null ? "none" : entry.typeName());
    }

    static RespValue rename(CommandContext ctx) {
        Database db = ctx.db();
        boolean renamed = db.rename(ctx.args().key(1), ctx.args().key(2));
        if (!renamed) {
            throw CommandException.noSuchKey();
        }
        return Replies.ok();
    }

    static RespValue persist(CommandContext ctx) {
        return Replies.integer(ctx.db().persist(ctx.args().key(1)) ? 1 : 0);
    }

    /**
     * Reports the remaining time to live. Replies -2 for a missing key and -1 for a key without TTL.
     *
     * @param ctx    the call context
     * @param unitMs milliseconds per reported unit: 1000 for TTL, 1 for PTTL
     */
    static RespValue ttl(CommandContext ctx, long unitMs) {
        Entry entry = ctx.db().get(ctx.args().key(1));
        if (entry == null) {
            return Replies.integer(-2);
        }
        if (!entry.hasExpiry()) {
            return Replies.integer(-1);
        }
        long remaining = Math.max(0, entry.expireAt() - ctx.db().now());
        return Replies.integer((remaining + unitMs / 2) / unitMs);
    }

    static RespValue dbsize(CommandContext ctx) {
        return Replies.integer(ctx.db().size());
    }

    static RespValue flushdb(CommandContext ctx) {
        ctx.db().clear();
        return Replies.ok();
    }

    static RespValue flushall(CommandContext ctx) {
        ctx.server().databases().flushAll();
        return Replies.ok();
    }

    /**
     * @param keys key names
     * @return the keys as bulk strings, in the same order
     */
    static List<RespValue> keysAsBulk(List<String> keys) {
        List<RespValue> out = new ArrayList<>(keys.size());
        for (String key : keys) {
            out.add(Replies.bulk(key.getBytes(StandardCharsets.ISO_8859_1)));
        }
        return out;
    }
}
