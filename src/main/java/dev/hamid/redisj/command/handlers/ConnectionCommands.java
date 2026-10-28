package dev.hamid.redisj.command.handlers;

import dev.hamid.redisj.command.Args;
import dev.hamid.redisj.command.Command;
import dev.hamid.redisj.command.CommandContext;
import dev.hamid.redisj.command.CommandException;
import dev.hamid.redisj.command.CommandRegistry;
import dev.hamid.redisj.command.Replies;
import dev.hamid.redisj.command.ServerState;
import dev.hamid.redisj.protocol.ProtocolVersion;
import dev.hamid.redisj.protocol.RespValue;
import dev.hamid.redisj.server.ClientConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;

/**
 * Connection commands: PING, ECHO, QUIT, SELECT, AUTH and HELLO.
 */
public final class ConnectionCommands {

    private ConnectionCommands() {
    }

    /**
     * Registers the commands.
     *
     * @param registry the registry to add to
     */
    public static void register(CommandRegistry registry) {
        registry.register(Command.of("PING", -1, Set.of(Command.Flag.FAST), 0, 0, 0, ConnectionCommands::ping));
        registry.register(Command.of("ECHO", 2, Set.of(Command.Flag.FAST), 0, 0, 0, ConnectionCommands::echo));
        registry.register(Command.of("QUIT", -1, Set.of(Command.Flag.FAST), 0, 0, 0, ConnectionCommands::quit));
        registry.register(Command.of("SELECT", 2, Set.of(Command.Flag.FAST), 0, 0, 0, ConnectionCommands::select));
        registry.register(Command.of("AUTH", -2, Set.of(Command.Flag.FAST), 0, 0, 0, ConnectionCommands::auth));
        registry.register(Command.of("HELLO", -1, Set.of(Command.Flag.FAST), 0, 0, 0, ConnectionCommands::hello));
    }

    static RespValue ping(CommandContext ctx) {
        Args args = ctx.args();
        if (args.size() == 1) {
            return new RespValue.SimpleString("PONG");
        }
        if (args.size() == 2) {
            return Replies.bulk(args.bytes(1));
        }
        throw new CommandException("ERR wrong number of arguments for 'ping' command");
    }

    static RespValue echo(CommandContext ctx) {
        return Replies.bulk(ctx.args().bytes(1));
    }

    static RespValue quit(CommandContext ctx) {
        ctx.client().reply(Replies.ok());
        ctx.client().closeAfterFlush();
        return null;
    }

    static RespValue select(CommandContext ctx) {
        long index = ctx.args().longValue(1);
        if (index < 0 || index >= ctx.server().databases().count()) {
            throw new CommandException("ERR DB index is out of range");
        }
        ctx.client().selectDb((int) index);
        return Replies.ok();
    }

    static RespValue auth(CommandContext ctx) {
        Args args = ctx.args();
        if (args.size() > 3) {
            throw CommandException.syntax();
        }
        ServerState state = ctx.server();
        if (!state.requiresPassword()) {
            throw new CommandException("ERR AUTH <password> called without any password configured for the "
                    + "default user. Are you sure your configuration is correct?");
        }
        String password = args.size() == 2 ? args.text(1) : null;
        if (args.size() == 3) {
            if (!"default".equals(args.text(1))) {
                throw new CommandException("WRONGPASS invalid username-password pair or user is disabled.");
            }
            password = args.text(2);
        }
        if (!passwordMatches(state, password)) {
            throw new CommandException("WRONGPASS invalid username-password pair or user is disabled.");
        }
        ctx.client().markAuthenticated();
        return Replies.ok();
    }

    static RespValue hello(CommandContext ctx) {
        Args args = ctx.args();
        ServerState state = ctx.server();
        ClientConnection client = ctx.client();
        int next = 1;
        ProtocolVersion version = client.protocol();
        if (args.size() > 1) {
            long requested = args.longValue(1);
            if (requested == 2) {
                version = ProtocolVersion.RESP2;
            } else if (requested == 3) {
                version = ProtocolVersion.RESP3;
            } else {
                throw new CommandException("NOPROTO unsupported protocol version");
            }
            next = 2;
        }
        String newName = null;
        while (next < args.size()) {
            String option = args.upper(next);
            if (option.equals("AUTH") && next + 2 < args.size()) {
                String user = args.text(next + 1);
                String password = args.text(next + 2);
                if (state.requiresPassword()
                        && (!"default".equals(user) || !passwordMatches(state, password))) {
                    throw new CommandException("WRONGPASS invalid username-password pair or user is disabled.");
                }
                client.markAuthenticated();
                next += 3;
            } else if (option.equals("SETNAME") && next + 1 < args.size()) {
                newName = args.text(next + 1);
                next += 2;
            } else {
                throw CommandException.syntax();
            }
        }
        if (!client.isAuthenticated()) {
            throw new CommandException("NOAUTH HELLO must be called with the client already authenticated, "
                    + "otherwise the HELLO <proto> AUTH <user> <pass> option can be used to authenticate the client "
                    + "and select the RESP protocol version at the same time");
        }
        if (newName != null) {
            ClientCommands.validateName(newName);
            client.setName(newName.isEmpty() ? null : newName);
        }
        client.setProtocol(version);
        return helloReply(client, version);
    }

    private static RespValue helloReply(ClientConnection client, ProtocolVersion version) {
        List<RespValue> fields = List.of(
                Replies.bulkText("server"), Replies.bulkText("redisj"),
                Replies.bulkText("version"), Replies.bulkText(ServerState.VERSION),
                Replies.bulkText("proto"), Replies.integer(version == ProtocolVersion.RESP3 ? 3 : 2),
                Replies.bulkText("id"), Replies.integer(client.id()),
                Replies.bulkText("mode"), Replies.bulkText("standalone"),
                Replies.bulkText("role"), Replies.bulkText("master"),
                Replies.bulkText("modules"), Replies.array(List.of()));
        return Replies.array(fields);
    }

    /**
     * Compares a password in constant time, so the check does not leak its prefix through timing.
     */
    static boolean passwordMatches(ServerState state, String candidate) {
        if (candidate == null) {
            return false;
        }
        byte[] expected = state.requirePass().orElse("").getBytes(StandardCharsets.UTF_8);
        byte[] actual = candidate.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
