package dev.hamid.redisj.command.handlers;

import dev.hamid.redisj.command.Args;
import dev.hamid.redisj.command.Command;
import dev.hamid.redisj.command.CommandContext;
import dev.hamid.redisj.command.CommandException;
import dev.hamid.redisj.command.CommandRegistry;
import dev.hamid.redisj.command.Deadlines;
import dev.hamid.redisj.command.Replies;
import dev.hamid.redisj.protocol.RespValue;
import dev.hamid.redisj.store.Database;
import dev.hamid.redisj.store.Entry;
import java.util.Set;

/**
 * Commands that set a time to live: EXPIRE, PEXPIRE, EXPIREAT and PEXPIREAT.
 *
 * <p>Each accepts one optional condition: NX (no TTL yet), XX (already has a TTL), GT (new TTL is
 * later than the current one) or LT (new TTL is earlier). A key without a TTL counts as infinite,
 * so GT never applies to it and LT always does.
 */
public final class ExpireCommands {

    private static final Set<Command.Flag> WRITE_FAST = Set.of(Command.Flag.WRITE, Command.Flag.FAST);

    private ExpireCommands() {
    }

    /**
     * Registers the commands.
     *
     * @param registry the registry to add to
     */
    public static void register(CommandRegistry registry) {
        registry.register(Command.of("EXPIRE", -3, WRITE_FAST, 1, 1, 1,
                ctx -> expire(ctx, "expire", 1000, false)));
        registry.register(Command.of("PEXPIRE", -3, WRITE_FAST, 1, 1, 1,
                ctx -> expire(ctx, "pexpire", 1, false)));
        registry.register(Command.of("EXPIREAT", -3, WRITE_FAST, 1, 1, 1,
                ctx -> expire(ctx, "expireat", 1000, true)));
        registry.register(Command.of("PEXPIREAT", -3, WRITE_FAST, 1, 1, 1,
                ctx -> expire(ctx, "pexpireat", 1, true)));
    }

    /**
     * @param ctx        the call context
     * @param name       lower-case command name for error messages
     * @param unitMillis milliseconds per amount unit
     * @param absolute   {@code true} if the amount is a Unix timestamp, {@code false} if relative
     */
    static RespValue expire(CommandContext ctx, String name, long unitMillis, boolean absolute) {
        Args args = ctx.args();
        String key = args.key(1);
        long amount = args.longValue(2);
        String condition = condition(args, 3);
        Database db = ctx.db();
        Entry entry = db.get(key);
        if (entry == null) {
            return Replies.integer(0);
        }
        long base = absolute ? 0 : db.now();
        long deadline = Deadlines.deadline(amount, unitMillis, base, name);
        if (!allowed(condition, entry.expireAt(), deadline)) {
            return Replies.integer(0);
        }
        Deadlines.apply(db, key, deadline);
        return Replies.integer(1);
    }

    /**
     * Parses the optional condition at the end of the arguments.
     *
     * @return the condition name, or {@code null} if none was given
     */
    static String condition(Args args, int from) {
        boolean nx = false;
        boolean xx = false;
        boolean gt = false;
        boolean lt = false;
        for (int i = from; i < args.size(); i++) {
            switch (args.upper(i)) {
                case "NX" -> nx = true;
                case "XX" -> xx = true;
                case "GT" -> gt = true;
                case "LT" -> lt = true;
                default -> throw new CommandException("ERR Unsupported option " + args.text(i));
            }
        }
        if (gt && lt) {
            throw new CommandException("ERR GT and LT options at the same time are not compatible");
        }
        if (nx && (xx || gt || lt) || xx && (gt || lt)) {
            throw new CommandException("ERR NX and XX, GT or LT options at the same time are not compatible");
        }
        if (nx) {
            return "NX";
        }
        if (xx) {
            return "XX";
        }
        if (gt) {
            return "GT";
        }
        return lt ? "LT" : null;
    }

    /**
     * Decides whether a new deadline may replace the current one under a condition.
     *
     * @param condition  NX, XX, GT, LT or {@code null}
     * @param current    current expiry, or {@link Entry#NO_EXPIRY}
     * @param deadline   the proposed deadline
     * @return {@code true} if the update should be applied
     */
    static boolean allowed(String condition, long current, long deadline) {
        boolean hasTtl = current != Entry.NO_EXPIRY;
        if (condition == null) {
            return true;
        }
        return switch (condition) {
            case "NX" -> !hasTtl;
            case "XX" -> hasTtl;
            case "GT" -> hasTtl && deadline > current;
            case "LT" -> !hasTtl || deadline < current;
            default -> true;
        };
    }
}
