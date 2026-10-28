package dev.hamid.redisj.command.handlers;

import dev.hamid.redisj.command.Command;
import dev.hamid.redisj.command.CommandContext;
import dev.hamid.redisj.command.CommandException;
import dev.hamid.redisj.command.CommandRegistry;
import dev.hamid.redisj.command.Numbers;
import dev.hamid.redisj.command.Replies;
import dev.hamid.redisj.protocol.RespValue;
import dev.hamid.redisj.store.Database;
import dev.hamid.redisj.store.Entry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Integer and float counters stored in string values: INCR, DECR, INCRBY, DECRBY and INCRBYFLOAT.
 */
public final class CounterCommands {

    private static final Set<Command.Flag> WRITE_FAST = Set.of(Command.Flag.WRITE, Command.Flag.FAST);

    private CounterCommands() {
    }

    /**
     * Registers the commands.
     *
     * @param registry the registry to add to
     */
    public static void register(CommandRegistry registry) {
        registry.register(Command.of("INCR", 2, WRITE_FAST, 1, 1, 1, ctx -> adjust(ctx, 1)));
        registry.register(Command.of("DECR", 2, WRITE_FAST, 1, 1, 1, ctx -> adjust(ctx, -1)));
        registry.register(Command.of("INCRBY", 3, WRITE_FAST, 1, 1, 1,
                ctx -> adjust(ctx, ctx.args().longValue(2))));
        registry.register(Command.of("DECRBY", 3, WRITE_FAST, 1, 1, 1, CounterCommands::decrby));
        registry.register(Command.of("INCRBYFLOAT", 3, WRITE_FAST, 1, 1, 1, CounterCommands::incrbyfloat));
    }

    /**
     * Adds a delta to an integer string, creating it as zero if missing. The TTL is kept.
     */
    static RespValue adjust(CommandContext ctx, long delta) {
        Database db = ctx.db();
        String key = ctx.args().key(1);
        Entry entry = db.get(key);
        byte[] current = StringCommands.bytesOf(entry);
        long base = 0;
        if (current != null) {
            OptionalLong parsed = Numbers.tryParseLong(current);
            if (parsed.isEmpty()) {
                throw CommandException.notInteger();
            }
            base = parsed.getAsLong();
        }
        long result;
        try {
            result = Math.addExact(base, delta);
        } catch (ArithmeticException e) {
            throw new CommandException("ERR increment or decrement would overflow");
        }
        db.put(key, Long.toString(result).getBytes(StandardCharsets.US_ASCII), true);
        return Replies.integer(result);
    }

    static RespValue decrby(CommandContext ctx) {
        long amount = ctx.args().longValue(2);
        if (amount == Long.MIN_VALUE) {
            throw new CommandException("ERR decrement would overflow");
        }
        return adjust(ctx, -amount);
    }

    static RespValue incrbyfloat(CommandContext ctx) {
        Database db = ctx.db();
        String key = ctx.args().key(1);
        double increment = ctx.args().doubleValue(2);
        byte[] current = StringCommands.bytesOf(db.get(key));
        double base = current == null ? 0 : Numbers.parseDouble(current);
        double result = base + increment;
        if (Double.isNaN(result) || Double.isInfinite(result)) {
            throw new CommandException("ERR increment would produce NaN or Infinity");
        }
        String text = BigDecimal.valueOf(result).stripTrailingZeros().toPlainString();
        db.put(key, text.getBytes(StandardCharsets.US_ASCII), true);
        return Replies.bulkText(text);
    }
}
