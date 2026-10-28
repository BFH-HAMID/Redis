package dev.hamid.redisj.command.handlers;

import dev.hamid.redisj.command.ClientTransaction;
import dev.hamid.redisj.command.Command;
import dev.hamid.redisj.command.CommandContext;
import dev.hamid.redisj.command.CommandException;
import dev.hamid.redisj.command.CommandRegistry;
import dev.hamid.redisj.command.CommandRunner;
import dev.hamid.redisj.command.DbKey;
import dev.hamid.redisj.command.Replies;
import dev.hamid.redisj.command.ServerState;
import dev.hamid.redisj.protocol.RespValue;
import dev.hamid.redisj.server.ClientConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Transactions: MULTI, EXEC, DISCARD, WATCH and UNWATCH.
 *
 * <p>Commands between MULTI and EXEC are queued and not run. EXEC runs them in order. A command
 * that fails at run time does not stop the others, as in Redis. A command rejected while queuing
 * (unknown name, wrong arity, or no auth) makes EXEC abort with EXECABORT. If a watched key was
 * modified, EXEC returns nil and runs nothing.
 */
public final class TransactionCommands {

    /** Commands that run immediately even inside MULTI. */
    public static final Set<String> CONTROL = Set.of("MULTI", "EXEC", "DISCARD", "WATCH", "UNWATCH");

    private TransactionCommands() {
    }

    /**
     * Registers the commands.
     *
     * @param registry the registry to add to
     */
    public static void register(CommandRegistry registry) {
        Set<Command.Flag> fast = Set.of(Command.Flag.FAST);
        registry.register(Command.of("MULTI", 1, fast, 0, 0, 0, TransactionCommands::multi));
        registry.register(Command.of("EXEC", 1, fast, 0, 0, 0, TransactionCommands::exec));
        registry.register(Command.of("DISCARD", 1, fast, 0, 0, 0, TransactionCommands::discard));
        registry.register(Command.of("WATCH", -2, fast, 1, -1, 1, TransactionCommands::watch));
        registry.register(Command.of("UNWATCH", 1, fast, 0, 0, 0, TransactionCommands::unwatch));
    }

    static RespValue multi(CommandContext ctx) {
        ClientTransaction tx = ctx.server().transaction(ctx.client());
        if (tx.inMulti()) {
            throw new CommandException("ERR MULTI calls can not be nested");
        }
        tx.begin();
        return Replies.ok();
    }

    static RespValue exec(CommandContext ctx) {
        ServerState state = ctx.server();
        ClientConnection client = ctx.client();
        ClientTransaction tx = state.findTransaction(client);
        if (tx == null || !tx.inMulti()) {
            throw new CommandException("ERR EXEC without MULTI");
        }
        boolean rejected = tx.queueFailed();
        boolean dirty = tx.watchDirty();
        List<ClientTransaction.Queued> queue = tx.takeQueue();
        state.watches().unwatchAll(tx);
        if (rejected) {
            return new RespValue.Error("EXECABORT Transaction discarded because of previous errors.");
        }
        if (dirty) {
            return Replies.nil();
        }
        List<RespValue> replies = new ArrayList<>(queue.size());
        for (ClientTransaction.Queued item : queue) {
            try {
                RespValue reply = CommandRunner.run(item.command(),
                        new CommandContext(client, item.args(), state));
                replies.add(reply == null ? Replies.nil() : reply);
            } catch (CommandException e) {
                replies.add(new RespValue.Error(e.getMessage()));
            }
        }
        return Replies.array(replies);
    }

    static RespValue discard(CommandContext ctx) {
        ServerState state = ctx.server();
        ClientTransaction tx = state.findTransaction(ctx.client());
        if (tx == null || !tx.inMulti()) {
            throw new CommandException("ERR DISCARD without MULTI");
        }
        tx.takeQueue();
        state.watches().unwatchAll(tx);
        return Replies.ok();
    }

    static RespValue watch(CommandContext ctx) {
        ServerState state = ctx.server();
        ClientTransaction tx = state.transaction(ctx.client());
        if (tx.inMulti()) {
            throw new CommandException("ERR WATCH inside MULTI is not allowed");
        }
        int db = ctx.client().db();
        for (int i = 1; i < ctx.args().size(); i++) {
            state.watches().watch(tx, new DbKey(db, ctx.args().key(i)));
        }
        return Replies.ok();
    }

    static RespValue unwatch(CommandContext ctx) {
        ClientTransaction tx = ctx.server().findTransaction(ctx.client());
        if (tx != null) {
            ctx.server().watches().unwatchAll(tx);
        }
        return Replies.ok();
    }
}
