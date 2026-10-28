package dev.hamid.redisj.server;

import dev.hamid.redisj.protocol.RespValue;
import java.util.List;
import java.util.Locale;

/**
 * Minimal request handler used until the command registry arrives in Phase 4.
 *
 * <p>Supports {@code PING [message]}, {@code ECHO message} and {@code QUIT}. Anything else gets
 * an {@code ERR unknown command} reply.
 */
public final class BasicRequestHandler implements RequestHandler {

    @Override
    public RespValue handle(ClientConnection client, RespValue request) {
        if (!(request instanceof RespValue.Array array)) {
            return error("ERR Protocol error: expected array");
        }
        List<RespValue> args = array.elements();
        if (args.isEmpty()) {
            return null;
        }
        if (!(args.get(0) instanceof RespValue.BulkString nameBytes)) {
            return error("ERR Protocol error: expected bulk string");
        }
        String name = nameBytes.asString().toUpperCase(Locale.ROOT);
        return switch (name) {
            case "PING" -> ping(args);
            case "ECHO" -> args.size() == 2
                    ? args.get(1)
                    : error("ERR wrong number of arguments for 'echo' command");
            case "QUIT" -> {
                client.reply(new RespValue.SimpleString("OK"));
                client.closeAfterFlush();
                yield null;
            }
            default -> error("ERR unknown command '" + nameBytes.asString() + "'");
        };
    }

    private static RespValue ping(List<RespValue> args) {
        return switch (args.size()) {
            case 1 -> new RespValue.SimpleString("PONG");
            case 2 -> args.get(1);
            default -> error("ERR wrong number of arguments for 'ping' command");
        };
    }

    private static RespValue error(String message) {
        return new RespValue.Error(message);
    }
}
