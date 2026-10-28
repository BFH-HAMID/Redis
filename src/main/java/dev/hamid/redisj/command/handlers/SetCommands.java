package dev.hamid.redisj.command.handlers;

import dev.hamid.redisj.command.Command;
import dev.hamid.redisj.command.CommandContext;
import dev.hamid.redisj.command.CommandException;
import dev.hamid.redisj.command.CommandRegistry;
import dev.hamid.redisj.command.Replies;
import dev.hamid.redisj.protocol.RespValue;
import dev.hamid.redisj.store.Database;
import dev.hamid.redisj.store.Entry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Set commands: SADD, SREM, SISMEMBER, SMEMBERS and SCARD.
 *
 * <p>A set is a {@link LinkedHashSet} of ISO-8859-1 member strings, which keeps members binary
 * safe. A set that becomes empty is removed from the keyspace.
 */
public final class SetCommands {

    private static final Set<Command.Flag> WRITE_FAST =
            Set.of(Command.Flag.WRITE, Command.Flag.FAST);
    private static final Set<Command.Flag> READ_FAST =
            Set.of(Command.Flag.READONLY, Command.Flag.FAST);

    private SetCommands() {
    }

    /**
     * Registers the commands.
     *
     * @param registry the registry to add to
     */
    public static void register(CommandRegistry registry) {
        registry.register(Command.of("SADD", -3, WRITE_FAST, 1, 1, 1, SetCommands::sadd));
        registry.register(Command.of("SREM", -3, WRITE_FAST, 1, 1, 1, SetCommands::srem));
        registry.register(Command.of("SISMEMBER", 3, READ_FAST, 1, 1, 1, SetCommands::sismember));
        registry.register(Command.of("SMEMBERS", 2, READ_FAST, 1, 1, 1, SetCommands::smembers));
        registry.register(Command.of("SCARD", 2, READ_FAST, 1, 1, 1, SetCommands::scard));
    }

    /**
     * @param entry the entry, possibly {@code null}
     * @return the set, or {@code null} if the key is missing
     * @throws CommandException with WRONGTYPE if the entry is not a set
     */
    static Set<String> setOf(Entry entry) {
        if (entry == null) {
            return null;
        }
        if (!(entry.value() instanceof LinkedHashSet<?>)) {
            throw CommandException.wrongType();
        }
        @SuppressWarnings("unchecked")
        Set<String> set = (Set<String>) entry.value();
        return set;
    }

    private static Set<String> setForWrite(Database db, String key) {
        Entry entry = db.get(key);
        if (entry == null) {
            Set<String> created = new LinkedHashSet<>();
            db.put(key, created, false);
            return created;
        }
        return setOf(entry);
    }

    static RespValue sadd(CommandContext ctx) {
        Set<String> set = setForWrite(ctx.db(), ctx.args().key(1));
        long added = 0;
        for (int i = 2; i < ctx.args().size(); i++) {
            if (set.add(ctx.args().key(i))) {
                added++;
            }
        }
        return Replies.integer(added);
    }

    static RespValue srem(CommandContext ctx) {
        Database db = ctx.db();
        String key = ctx.args().key(1);
        Set<String> set = setOf(db.get(key));
        if (set == null) {
            return Replies.integer(0);
        }
        long removed = 0;
        for (int i = 2; i < ctx.args().size(); i++) {
            if (set.remove(ctx.args().key(i))) {
                removed++;
            }
        }
        if (set.isEmpty()) {
            db.remove(key);
        }
        return Replies.integer(removed);
    }

    static RespValue sismember(CommandContext ctx) {
        Set<String> set = setOf(ctx.db().get(ctx.args().key(1)));
        boolean present = set != null && set.contains(ctx.args().key(2));
        return Replies.integer(present ? 1 : 0);
    }

    static RespValue smembers(CommandContext ctx) {
        Set<String> set = setOf(ctx.db().get(ctx.args().key(1)));
        List<String> members = set == null ? List.of() : new ArrayList<>(set);
        return Replies.array(KeyCommands.keysAsBulk(members));
    }

    static RespValue scard(CommandContext ctx) {
        Set<String> set = setOf(ctx.db().get(ctx.args().key(1)));
        return Replies.integer(set == null ? 0 : set.size());
    }
}
