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
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * List commands: LPUSH, RPUSH, LPOP, RPOP, LLEN, LRANGE and LINDEX.
 *
 * <p>A list is a {@link LinkedList} of byte arrays. Pushes and pops at either end are O(1).
 * Index access (LINDEX, LRANGE) is O(n), which is a known cost. A list that becomes empty is
 * removed from the keyspace, as in Redis.
 */
public final class ListCommands {

    private static final Set<Command.Flag> WRITE_FAST = Set.of(Command.Flag.WRITE, Command.Flag.FAST);
    private static final Set<Command.Flag> READ_FAST = Set.of(Command.Flag.READONLY, Command.Flag.FAST);

    private ListCommands() {
    }

    /**
     * Registers the commands.
     *
     * @param registry the registry to add to
     */
    public static void register(CommandRegistry registry) {
        registry.register(Command.of("LPUSH", -3, WRITE_FAST, 1, 1, 1, ctx -> push(ctx, true)));
        registry.register(Command.of("RPUSH", -3, WRITE_FAST, 1, 1, 1, ctx -> push(ctx, false)));
        registry.register(Command.of("LPOP", -2, WRITE_FAST, 1, 1, 1, ctx -> pop(ctx, true)));
        registry.register(Command.of("RPOP", -2, WRITE_FAST, 1, 1, 1, ctx -> pop(ctx, false)));
        registry.register(Command.of("LLEN", 2, READ_FAST, 1, 1, 1, ListCommands::llen));
        registry.register(Command.of("LRANGE", 4, READ_FAST, 1, 1, 1, ListCommands::lrange));
        registry.register(Command.of("LINDEX", 3, READ_FAST, 1, 1, 1, ListCommands::lindex));
    }

    /**
     * @param entry the entry, possibly {@code null}
     * @return the list, or {@code null} if the key is missing
     * @throws CommandException with WRONGTYPE if the entry is not a list
     */
    static LinkedList<byte[]> listOf(Entry entry) {
        if (entry == null) {
            return null;
        }
        if (!(entry.value() instanceof LinkedList<?>)) {
            throw CommandException.wrongType();
        }
        @SuppressWarnings("unchecked")
        LinkedList<byte[]> list = (LinkedList<byte[]>) entry.value();
        return list;
    }

    private static LinkedList<byte[]> listForWrite(Database db, String key) {
        Entry entry = db.get(key);
        if (entry == null) {
            LinkedList<byte[]> created = new LinkedList<>();
            db.put(key, created, false);
            return created;
        }
        return listOf(entry);
    }

    static RespValue push(CommandContext ctx, boolean head) {
        Database db = ctx.db();
        LinkedList<byte[]> list = listForWrite(db, ctx.args().key(1));
        for (int i = 2; i < ctx.args().size(); i++) {
            byte[] value = ctx.args().bytes(i);
            if (head) {
                list.addFirst(value);
            } else {
                list.addLast(value);
            }
        }
        return Replies.integer(list.size());
    }

    static RespValue pop(CommandContext ctx, boolean head) {
        Args args = ctx.args();
        String name = head ? "lpop" : "rpop";
        if (args.size() > 3) {
            throw new CommandException("ERR wrong number of arguments for '" + name + "' command");
        }
        boolean withCount = args.size() == 3;
        long count = 1;
        if (withCount) {
            count = args.longValue(2);
            if (count < 0) {
                throw new CommandException("ERR value is out of range, must be positive");
            }
        }
        Database db = ctx.db();
        String key = args.key(1);
        LinkedList<byte[]> list = listOf(db.get(key));
        if (list == null) {
            return Replies.nil();
        }
        long n = Math.min(count, list.size());
        List<RespValue> popped = new ArrayList<>();
        for (long i = 0; i < n; i++) {
            byte[] value = head ? list.removeFirst() : list.removeLast();
            popped.add(Replies.bulk(value));
        }
        if (list.isEmpty()) {
            db.remove(key);
        }
        if (!withCount) {
            return popped.isEmpty() ? Replies.nil() : popped.get(0);
        }
        return Replies.array(popped);
    }

    static RespValue llen(CommandContext ctx) {
        LinkedList<byte[]> list = listOf(ctx.db().get(ctx.args().key(1)));
        return Replies.integer(list == null ? 0 : list.size());
    }

    static RespValue lrange(CommandContext ctx) {
        LinkedList<byte[]> list = listOf(ctx.db().get(ctx.args().key(1)));
        if (list == null) {
            return Replies.array(new ArrayList<>());
        }
        long len = list.size();
        long start = ctx.args().longValue(2);
        long stop = ctx.args().longValue(3);
        if (start < 0) {
            start += len;
        }
        if (stop < 0) {
            stop += len;
        }
        start = Math.max(start, 0);
        if (start > stop || start >= len) {
            return Replies.array(new ArrayList<>());
        }
        stop = Math.min(stop, len - 1);
        List<RespValue> out = new ArrayList<>();
        for (byte[] value : list.subList((int) start, (int) stop + 1)) {
            out.add(Replies.bulk(value));
        }
        return Replies.array(out);
    }

    static RespValue lindex(CommandContext ctx) {
        LinkedList<byte[]> list = listOf(ctx.db().get(ctx.args().key(1)));
        if (list == null) {
            return Replies.nil();
        }
        long len = list.size();
        long index = ctx.args().longValue(2);
        if (index < 0) {
            index += len;
        }
        if (index < 0 || index >= len) {
            return Replies.nil();
        }
        return Replies.bulk(list.get((int) index));
    }
}
