package dev.hamid.redisj.server;

import dev.hamid.redisj.protocol.RespValue;
import java.util.List;

/**
 * Callbacks from client I/O to the server. Implementations may be called on any I/O loop thread and
 * must hand the work off to the command-execution loop.
 */
interface ClientEvents {

    /**
     * Delivers parsed requests in arrival order. The batch may end with a protocol error, which
     * must be answered after the requests are executed and then closes the connection.
     *
     * @param client        the connection the requests came from
     * @param requests      complete requests, in order; possibly empty
     * @param protocolError description of a protocol error that ended the batch, or {@code null}
     */
    void onRequests(ClientConnection client, List<RespValue> requests, String protocolError);

    /**
     * Called once the connection's channel has been closed.
     *
     * @param client the closed connection
     */
    void onClosed(ClientConnection client);
}
