package dev.hamid.redisj.command.handlers;

import dev.hamid.redisj.command.Args;
import dev.hamid.redisj.command.Command;
import dev.hamid.redisj.command.CommandContext;
import dev.hamid.redisj.command.CommandException;
import dev.hamid.redisj.command.CommandRegistry;
import dev.hamid.redisj.protocol.RespValue;
import dev.hamid.redisj.pubsub.PubSub;
import dev.hamid.redisj.server.ClientConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Publish/subscribe commands: SUBSCRIBE, UNSUBSCRIBE, PSUBSCRIBE, PUNSUBSCRIBE, PUBLISH and PUBSUB.
 *
 * <p>A subscribe command with several names sends one confirmation per name. The first ones are
 * pushed to the client directly, and the last one is returned as the command's reply, so the
 * replies keep their order.
 */
public final class PubSubCommands {

    /** Commands a connection with at least one subscription may still run (RESP2 rules). */
    public static final Set<String> SUBSCRIBED_COMMANDS = Set.of(
            "SUBSCRIBE", "UNSUBSCRIBE", "PSUBSCRIBE", "PUNSUBSCRIBE", "PING", "QUIT", "RESET");

    private static final Set<Command.Flag> NONE = Set.of();

    private PubSubCommands() {
    }

    /**
     * Registers the commands.
     *
     * @param registry the registry to add to
     */
    public static void register(CommandRegistry registry) {
        registry.register(Command.of("SUBSCRIBE", -2, NONE, 0, 0, 0, PubSubCommands::subscribe));
        registry.register(Command.of("UNSUBSCRIBE", -1, NONE, 0, 0, 0, PubSubCommands::unsubscribe));
        registry.register(Command.of("PSUBSCRIBE", -2, NONE, 0, 0, 0, PubSubCommands::psubscribe));
        registry.register(Command.of("PUNSUBSCRIBE", -1, NONE, 0, 0, 0, PubSubCommands::punsubscribe));
        registry.register(Command.of("PUBLISH", 3, NONE, 0, 0, 0, PubSubCommands::publish));
        registry.register(Command.of("PUBSUB", -2, NONE, 0, 0, 0, PubSubCommands::pubsub));
    }

    /**
     * The reply to PING while a connection is subscribed: {@code ["pong", message]}, where the
     * message is empty when none was given.
     *
     * @param args the PING call, with one or two arguments
     * @return the reply
     */
    public static RespValue subscribedPing(Args args) {
        byte[] message = args.size() == 2 ? args.bytes(1) : new byte[0];
        return new RespValue.Array(List.of(bulk("pong"), new RespValue.BulkString(message)));
    }

    static RespValue subscribe(CommandContext ctx) {
        return confirmAll(ctx, "subscribe", namesFrom(ctx.args()),
                (ps, client, name) -> ps.subscribe(client, name));
    }

    static RespValue psubscribe(CommandContext ctx) {
        return confirmAll(ctx, "psubscribe", namesFrom(ctx.args()),
                (ps, client, name) -> ps.psubscribe(client, name));
    }

    static RespValue unsubscribe(CommandContext ctx) {
        PubSub ps = ctx.server().pubsub();
        List<String> names = ctx.args().size() == 1
                ? ps.channelsOf(ctx.client()) : namesFrom(ctx.args());
        if (names.isEmpty()) {
            return confirmation("unsubscribe", null, ps.totalFor(ctx.client()));
        }
        return confirmAll(ctx, "unsubscribe", names,
                (p, client, name) -> p.unsubscribe(client, name));
    }

    static RespValue punsubscribe(CommandContext ctx) {
        PubSub ps = ctx.server().pubsub();
        List<String> names = ctx.args().size() == 1
                ? ps.patternsOf(ctx.client()) : namesFrom(ctx.args());
        if (names.isEmpty()) {
            return confirmation("punsubscribe", null, ps.totalFor(ctx.client()));
        }
        return confirmAll(ctx, "punsubscribe", names,
                (p, client, name) -> p.punsubscribe(client, name));
    }

    static RespValue publish(CommandContext ctx) {
        int receivers = ctx.server().pubsub().publish(ctx.args().key(1), ctx.args().bytes(2));
        return new RespValue.Integer(receivers);
    }

    static RespValue pubsub(CommandContext ctx) {
        Args args = ctx.args();
        PubSub ps = ctx.server().pubsub();
        String sub = args.upper(1);
        if (sub.equals("CHANNELS")) {
            String pattern = args.size() > 2 ? args.key(2) : null;
            List<RespValue> items = new ArrayList<>();
            for (String channel : ps.activeChannels(pattern)) {
                items.add(bulkKey(channel));
            }
            return new RespValue.Array(items);
        }
        if (sub.equals("NUMSUB")) {
            List<RespValue> items = new ArrayList<>();
            for (int i = 2; i < args.size(); i++) {
                String channel = args.key(i);
                items.add(bulkKey(channel));
                items.add(new RespValue.Integer(ps.subscriberCount(channel)));
            }
            return new RespValue.Array(items);
        }
        if (sub.equals("NUMPAT")) {
            return new RespValue.Integer(ps.patternCount());
        }
        throw new CommandException("ERR unknown subcommand '" + args.text(1) + "'. Try PUBSUB HELP.");
    }

    /** Subscribes or unsubscribes each name and pushes the confirmations in order. */
    private static RespValue confirmAll(CommandContext ctx, String kind, List<String> names,
                                        Action action) {
        ClientConnection client = ctx.client();
        PubSub ps = ctx.server().pubsub();
        RespValue last = null;
        for (String name : names) {
            int count = action.apply(ps, client, name);
            if (last != null) {
                client.reply(last);
            }
            last = confirmation(kind, name, count);
        }
        return last;
    }

    private static RespValue confirmation(String kind, String name, int count) {
        return new RespValue.Array(List.of(bulk(kind),
                name == null ? new RespValue.Null() : bulkKey(name),
                new RespValue.Integer(count)));
    }

    private static List<String> namesFrom(Args args) {
        List<String> names = new ArrayList<>(args.size() - 1);
        for (int i = 1; i < args.size(); i++) {
            names.add(args.key(i));
        }
        return names;
    }

    private static RespValue bulk(String text) {
        return new RespValue.BulkString(text.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    private static RespValue bulkKey(String name) {
        return new RespValue.BulkString(name.getBytes(StandardCharsets.ISO_8859_1));
    }

    @FunctionalInterface
    private interface Action {
        int apply(PubSub ps, ClientConnection client, String name);
    }
}
