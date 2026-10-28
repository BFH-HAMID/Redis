package dev.hamid.redisj.command;

import dev.hamid.redisj.command.handlers.ClientCommands;
import dev.hamid.redisj.command.handlers.ConnectionCommands;
import dev.hamid.redisj.command.handlers.CounterCommands;
import dev.hamid.redisj.command.handlers.ExpireCommands;
import dev.hamid.redisj.command.handlers.HashCommands;
import dev.hamid.redisj.command.handlers.KeyCommands;
import dev.hamid.redisj.command.handlers.ListCommands;
import dev.hamid.redisj.command.handlers.ScanCommands;
import dev.hamid.redisj.command.handlers.SetCommands;
import dev.hamid.redisj.command.handlers.StringCommands;
import dev.hamid.redisj.command.handlers.TransactionCommands;
import dev.hamid.redisj.protocol.RespValue;
import dev.hamid.redisj.server.ClientConnection;
import dev.hamid.redisj.server.RequestHandler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Routes client requests to commands.
 *
 * <p>For each request the registry checks, in order: the request shape, that the command exists,
 * its arity, and authentication. A request that fails one of these checks is rejected. Inside
 * {@code MULTI} a valid request is queued and answered with {@code QUEUED}, and a rejected request
 * also marks the transaction so that {@code EXEC} aborts. Otherwise the command runs through
 * {@link CommandRunner}, and a {@link CommandException} becomes an error reply.
 *
 * <p>The registry also drives the active expiry cycle from {@link #tick()} and releases
 * transaction state when a client closes.
 *
 * <p>Runs only on the execution loop.
 */
public final class CommandRegistry implements RequestHandler {

    private final ServerState state;
    private final Map<String, Command> commands = new HashMap<>();

    /**
     * @param state the shared server state
     */
    public CommandRegistry(ServerState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    /**
     * Creates a registry with every built-in command.
     *
     * @param state the shared server state
     * @return a registry with all commands registered
     */
    public static CommandRegistry withDefaults(ServerState state) {
        CommandRegistry registry = new CommandRegistry(state);
        ConnectionCommands.register(registry);
        ClientCommands.register(registry);
        KeyCommands.register(registry);
        ExpireCommands.register(registry);
        ScanCommands.register(registry);
        StringCommands.register(registry);
        ListCommands.register(registry);
        HashCommands.register(registry);
        SetCommands.register(registry);
        CounterCommands.register(registry);
        TransactionCommands.register(registry);
        return registry;
    }

    /**
     * Adds a command. A later registration with the same name replaces the earlier one.
     *
     * @param command the command
     */
    public void register(Command command) {
        commands.put(command.name(), command);
    }

    /**
     * @param name the command name, in any case
     * @return the command, if registered
     */
    public Optional<Command> lookup(String name) {
        return Optional.ofNullable(commands.get(name.toUpperCase(Locale.ROOT)));
    }

    /**
     * @return the number of registered commands
     */
    public int size() {
        return commands.size();
    }

    @Override
    public RespValue handle(ClientConnection client, RespValue request) {
        if (!(request instanceof RespValue.Array array)) {
            return error("ERR Protocol error: expected array");
        }
        List<RespValue> elements = array.elements();
        if (elements.isEmpty()) {
            return null;
        }
        List<byte[]> raw = new ArrayList<>(elements.size());
        for (RespValue element : elements) {
            if (!(element instanceof RespValue.BulkString bulk)) {
                return error("ERR Protocol error: expected bulk string");
            }
            raw.add(bulk.bytes());
        }
        Args args = new Args(raw);
        String name = args.upper(0);
        ClientTransaction tx = state.findTransaction(client);
        Command command = commands.get(name);
        if (command == null) {
            return rejected(tx, "ERR unknown command '" + args.text(0) + "', with args beginning with: "
                    + preview(args));
        }
        if (!arityMatches(command.arity(), args.size())) {
            return rejected(tx, "ERR wrong number of arguments for '" + name.toLowerCase(Locale.ROOT)
                    + "' command");
        }
        if (state.requiresPassword() && !client.isAuthenticated() && !isAuthExempt(name)) {
            return rejected(tx, "NOAUTH Authentication required.");
        }
        if (tx != null && tx.inMulti() && !TransactionCommands.CONTROL.contains(name)) {
            tx.enqueue(command, args);
            return new RespValue.SimpleString("QUEUED");
        }
        try {
            return CommandRunner.run(command, new CommandContext(client, args, state));
        } catch (CommandException e) {
            return error(e.getMessage());
        }
    }

    /**
     * Runs one active expiry cycle. Called by the server about every 100 ms.
     */
    @Override
    public void tick() {
        state.expiry().runActiveCycle();
        state.writeLog().tick();
    }

    /**
     * Releases a closed client's transaction state and watches.
     */
    @Override
    public void onClientClosed(ClientConnection client) {
        state.releaseClient(client);
    }

    /**
     * @param arity the registered arity
     * @param count the number of arguments sent, including the command name
     * @return {@code true} if the count is acceptable
     */
    static boolean arityMatches(int arity, int count) {
        return arity >= 0 ? count == arity : count >= -arity;
    }

    private static boolean isAuthExempt(String name) {
        return name.equals("AUTH") || name.equals("HELLO");
    }

    private static String preview(Args args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < args.size(); i++) {
            sb.append('\'').append(args.text(i)).append("' ");
        }
        return sb.toString();
    }

    /** Returns an error for a request that failed validation, marking an open MULTI as failed. */
    private static RespValue rejected(ClientTransaction tx, String message) {
        if (tx != null && tx.inMulti()) {
            tx.markQueueFailed();
        }
        return error(message);
    }

    private static RespValue error(String message) {
        return new RespValue.Error(message);
    }
}
