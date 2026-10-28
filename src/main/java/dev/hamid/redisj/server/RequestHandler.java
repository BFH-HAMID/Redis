package dev.hamid.redisj.server;

import dev.hamid.redisj.protocol.RespValue;

/**
 * Executes one client request and produces its reply. Implementations run only on the
 * command-execution loop, so they need no locking for per-connection state.
 */
@FunctionalInterface
public interface RequestHandler {

    /**
     * Handles one request.
     *
     * @param client  the connection that sent the request
     * @param request the request, normally an array of bulk strings
     * @return the reply to send, or {@code null} to send nothing
     */
    RespValue handle(ClientConnection client, RespValue request);

    /**
     * Periodic housekeeping, called on the execution loop about every 100 ms. The default does
     * nothing.
     */
    default void tick() {
    }

    /**
     * Called on the execution loop after a client has closed, so the handler can release its
     * per-client state. The default does nothing.
     *
     * @param client the connection that closed
     */
    default void onClientClosed(ClientConnection client) {
    }
}
