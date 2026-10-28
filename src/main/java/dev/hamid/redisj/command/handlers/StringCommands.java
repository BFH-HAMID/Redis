package dev.hamid.redisj.command.handlers;

import dev.hamid.redisj.command.Args;
import dev.hamid.redisj.command.Command;
import dev.hamid.redisj.command.CommandContext;
import dev.hamid.redisj.command.CommandException;
import dev.hamid.redisj.command.CommandRegistry;
import dev.hamid.redisj.command.Deadlines;
import dev.hamid.redisj.command.Replies;
import dev.hamid.redisj.protocol.RespParser;
import dev.hamid.redisj.protocol.RespValue;
import dev.hamid.redisj.store.Database;
import dev.hamid.redisj.store.Entry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * String commands: GET, SET, GETSET, MGET, MSET, APPEND, STRLEN, GETRANGE and SETRANGE.
 */
public final class StringCommands {

    /** Largest string Redis allows, which matches the protocol's bulk length limit. */
    static final long MAX_STRING_BYTES = RespParser.MAX_BULK_LENGTH;

    private static final Set<Command.Flag> READ_FAST = Set.of(Command.Flag.READONLY, Command.Flag.FAST);
    private static final Set<Command.Flag> WRITE_FAST = Set.of(Command.Flag.WRITE, Command.Flag.FAST);
    private static final Set<Command.Flag> WRITE = Set.of(Command.Flag.WRITE);

    private StringCommands() {
    }

    /**
     * Registers the commands.
     *
     * @param registry the registry to add to
     */
    public static void register(CommandRegistry registry) {
        registry.register(Command.of("GET", 2, READ_FAST, 1, 1, 1, StringCommands::get));
        registry.register(Command.of("SET", -3, WRITE_FAST, 1, 1, 1, StringCommands::set));
        registry.register(Command.of("GETSET", 3, WRITE_FAST, 1, 1, 1, StringCommands::getset));
        registry.register(Command.of("MGET", -2, READ_FAST, 1, -1, 1, StringCommands::mget));
        registry.register(Command.of("MSET", -3, WRITE, 1, -1, 2, StringCommands::mset));
        registry.register(Command.of("APPEND", 3, WRITE_FAST, 1, 1, 1, StringCommands::append));
        registry.register(Command.of("STRLEN", 2, READ_FAST, 1, 1, 1, StringCommands::strlen));
        registry.register(Command.of("GETRANGE", 4, READ_FAST, 1, 1, 1, StringCommands::getrange));
        registry.register(Command.of("SETRANGE", 4, WRITE, 1, 1, 1, StringCommands::setrange));
    }

    /**
     * Returns the bytes of a string entry.
     *
     * @param entry the entry, possibly {@code null}
     * @return the string bytes, or {@code null} if the entry is missing
     * @throws CommandException with WRONGTYPE if the entry is not a string
     */
    static byte[] bytesOf(Entry entry) {
        if (entry == null) {
            return null;
        }
        if (!(entry.value() instanceof byte[] bytes)) {
            throw CommandException.wrongType();
        }
        return bytes;
    }

    static RespValue get(CommandContext ctx) {
        byte[] bytes = bytesOf(ctx.db().get(ctx.args().key(1)));
        return bytes == null ? Replies.nil() : Replies.bulk(bytes);
    }

    static RespValue set(CommandContext ctx) {
        Args args = ctx.args();
        Database db = ctx.db();
        String key = args.key(1);
        byte[] value = args.bytes(2);
        Options options = Options.parse(args);

        Entry existing = db.get(key);
        RespValue previous = options.get() ? previousReply(existing) : null;
        boolean exists = existing != null;
        if ((options.nx() && exists) || (options.xx() && !exists)) {
            return options.get() ? previous : Replies.nil();
        }
        db.put(key, value, options.keepTtl());
        if (options.expireUnit() != 0) {
            long deadline = options.deadline(db.now());
            Deadlines.apply(db, key, deadline);
        }
        return options.get() ? previous : Replies.ok();
    }

    static RespValue getset(CommandContext ctx) {
        Database db = ctx.db();
        String key = ctx.args().key(1);
        RespValue previous = previousReply(db.get(key));
        db.put(key, ctx.args().bytes(2), false);
        return previous;
    }

    static RespValue mget(CommandContext ctx) {
        Database db = ctx.db();
        List<RespValue> values = new ArrayList<>(ctx.args().size() - 1);
        for (int i = 1; i < ctx.args().size(); i++) {
            Entry entry = db.get(ctx.args().key(i));
            byte[] bytes = entry != null && entry.value() instanceof byte[] b ? b : null;
            values.add(bytes == null ? Replies.nil() : Replies.bulk(bytes));
        }
        return Replies.array(values);
    }

    static RespValue mset(CommandContext ctx) {
        Args args = ctx.args();
        if (args.size() % 2 == 0) {
            throw new CommandException("ERR wrong number of arguments for 'mset' command");
        }
        Database db = ctx.db();
        for (int i = 1; i < args.size(); i += 2) {
            db.put(args.key(i), args.bytes(i + 1), false);
        }
        return Replies.ok();
    }

    static RespValue append(CommandContext ctx) {
        Database db = ctx.db();
        String key = ctx.args().key(1);
        byte[] current = bytesOf(db.get(key));
        byte[] suffix = ctx.args().bytes(2);
        byte[] joined = concat(current, suffix);
        db.put(key, joined, true);
        return Replies.integer(joined.length);
    }

    static RespValue strlen(CommandContext ctx) {
        byte[] bytes = bytesOf(ctx.db().get(ctx.args().key(1)));
        return Replies.integer(bytes == null ? 0 : bytes.length);
    }

    static RespValue getrange(CommandContext ctx) {
        byte[] bytes = bytesOf(ctx.db().get(ctx.args().key(1)));
        if (bytes == null) {
            return Replies.bulk(new byte[0]);
        }
        long length = bytes.length;
        long start = ctx.args().longValue(2);
        long end = ctx.args().longValue(3);
        if (start < 0) {
            start = length + start;
        }
        if (end < 0) {
            end = length + end;
        }
        start = Math.max(start, 0);
        end = Math.max(end, 0);
        end = Math.min(end, length - 1);
        if (length == 0 || start > end) {
            return Replies.bulk(new byte[0]);
        }
        byte[] slice = new byte[(int) (end - start + 1)];
        System.arraycopy(bytes, (int) start, slice, 0, slice.length);
        return Replies.bulk(slice);
    }

    static RespValue setrange(CommandContext ctx) {
        Args args = ctx.args();
        Database db = ctx.db();
        String key = args.key(1);
        long offset = args.longValue(2);
        if (offset < 0) {
            throw new CommandException("ERR offset is out of range");
        }
        byte[] patch = args.bytes(3);
        byte[] current = bytesOf(db.get(key));
        int currentLength = current == null ? 0 : current.length;
        if (patch.length == 0) {
            return Replies.integer(currentLength);
        }
        if (offset > MAX_STRING_BYTES - patch.length) {
            throw new CommandException("ERR string exceeds maximum allowed size (proto-max-bulk-len)");
        }
        long needed = offset + patch.length;
        byte[] result = new byte[(int) Math.max(currentLength, needed)];
        if (current != null) {
            System.arraycopy(current, 0, result, 0, current.length);
        }
        System.arraycopy(patch, 0, result, (int) offset, patch.length);
        db.put(key, result, true);
        return Replies.integer(result.length);
    }

    private static RespValue previousReply(Entry existing) {
        byte[] bytes = bytesOf(existing);
        return bytes == null ? Replies.nil() : Replies.bulk(bytes);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        if (first == null) {
            return second.clone();
        }
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    /**
     * The options accepted by SET after the key and value.
     *
     * @param nx       only set if the key does not exist
     * @param xx       only set if the key exists
     * @param get      reply with the previous value
     * @param keepTtl  keep the existing TTL
     * @param expireUnit the TTL unit name, or 0 when no expiry option was given
     * @param amount   the expiry amount, in the unit given by {@code expireUnit}
     */
    private record Options(boolean nx, boolean xx, boolean get, boolean keepTtl, int expireUnit,
                           long amount, boolean absolute, long unitMillis) {

        static Options parse(Args args) {
            boolean nx = false;
            boolean xx = false;
            boolean get = false;
            boolean keepTtl = false;
            String expireOption = null;
            long amount = 0;
            for (int i = 3; i < args.size(); i++) {
                String option = args.upper(i);
                switch (option) {
                    case "NX" -> nx = true;
                    case "XX" -> xx = true;
                    case "GET" -> get = true;
                    case "KEEPTTL" -> keepTtl = true;
                    case "EX", "PX", "EXAT", "PXAT" -> {
                        if (expireOption != null || i + 1 >= args.size()) {
                            throw CommandException.syntax();
                        }
                        expireOption = option;
                        amount = args.longValue(++i);
                        if (amount <= 0) {
                            throw new CommandException("ERR invalid expire time in 'set' command");
                        }
                    }
                    default -> throw CommandException.syntax();
                }
            }
            if (nx && xx || keepTtl && expireOption != null) {
                throw CommandException.syntax();
            }
            if (expireOption == null) {
                return new Options(nx, xx, get, keepTtl, 0, 0, false, 0);
            }
            boolean absolute = expireOption.endsWith("AT");
            long unit = expireOption.startsWith("E") ? 1000 : 1;
            return new Options(nx, xx, get, keepTtl, 1, amount, absolute, unit);
        }

        /**
         * @param now the current time in epoch milliseconds
         * @return the absolute deadline in epoch milliseconds
         */
        long deadline(long now) {
            return Deadlines.deadline(amount, unitMillis, absolute ? 0 : now, "set");
        }
    }
}
