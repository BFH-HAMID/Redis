package dev.hamid.redisj.command.handlers;

import dev.hamid.redisj.command.Args;
import dev.hamid.redisj.command.Command;
import dev.hamid.redisj.command.CommandContext;
import dev.hamid.redisj.command.CommandException;
import dev.hamid.redisj.command.CommandRegistry;
import dev.hamid.redisj.command.Replies;
import dev.hamid.redisj.protocol.RespValue;
import dev.hamid.redisj.server.ClientConnection;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Comparator;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code CLIENT} command and its subcommands: SETNAME, GETNAME, ID, LIST and SETINFO.
 */
public final class ClientCommands {

    private static final Pattern BAD_NAME = Pattern.compile("[\\x00-\\x20\\x7f]");

    private ClientCommands() {
    }

    /**
     * Registers the command.
     *
     * @param registry the registry to add to
     */
    public static void register(CommandRegistry registry) {
        registry.register(Command.of("CLIENT", -2, Set.of(Command.Flag.ADMIN), 0, 0, 0, ClientCommands::client));
    }

    static RespValue client(CommandContext ctx) {
        Args args = ctx.args();
        String sub = args.upper(1);
        return switch (sub) {
            case "SETNAME" -> setName(ctx, args);
            case "GETNAME" -> getName(ctx, args);
            case "ID" -> Replies.integer(ctx.client().id());
            case "LIST" -> list(ctx);
            case "SETINFO" -> setInfo(args);
            default -> throw new CommandException("ERR unknown subcommand '" + args.text(1)
                    + "'. Try CLIENT HELP.");
        };
    }

    /**
     * Rejects names with spaces or control characters, as Redis does.
     *
     * @param name the proposed client name
     * @throws CommandException if the name is invalid
     */
    public static void validateName(String name) {
        if (BAD_NAME.matcher(name).find()) {
            throw new CommandException("ERR Client names cannot contain spaces, newlines or special characters.");
        }
    }

    private static RespValue setName(CommandContext ctx, Args args) {
        if (args.size() != 3) {
            throw new CommandException("ERR wrong number of arguments for 'client|setname' command");
        }
        String name = args.text(2);
        validateName(name);
        ctx.client().setName(name.isEmpty() ? null : name);
        return Replies.ok();
    }

    private static RespValue getName(CommandContext ctx, Args args) {
        if (args.size() != 2) {
            throw new CommandException("ERR wrong number of arguments for 'client|getname' command");
        }
        return ctx.client().name().map(Replies::bulkText).orElse(Replies.nil());
    }

    private static RespValue setInfo(Args args) {
        if (args.size() != 4) {
            throw new CommandException("ERR wrong number of arguments for 'client|setinfo' command");
        }
        String attribute = args.upper(2);
        if (!attribute.equals("LIB-NAME") && !attribute.equals("LIB-VER")) {
            throw new CommandException("ERR Unrecognized option '" + args.text(2) + "'");
        }
        return Replies.ok();
    }

    private static RespValue list(CommandContext ctx) {
        Collection<ClientConnection> clients = ctx.server().clients();
        StringBuilder out = new StringBuilder();
        clients.stream()
                .sorted(Comparator.comparingLong(ClientConnection::id))
                .forEach(c -> out.append("id=").append(c.id())
                        .append(" addr=").append(c.remoteAddress())
                        .append(" name=").append(c.name().orElse(""))
                        .append(" db=").append(c.db())
                        .append('\n'));
        return Replies.bulk(out.toString().getBytes(StandardCharsets.UTF_8));
    }
}
