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
}
