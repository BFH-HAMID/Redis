package dev.hamid.redisj.command;

import dev.hamid.redisj.server.ClientConnection;
import dev.hamid.redisj.store.Database;

/**
 * Everything a command needs for one call: the client, its arguments and the server state.
 *
 * @param client the connection that sent the command
 * @param args   the arguments, including the command name
 * @param server shared server state
 */
public record CommandContext(ClientConnection client, Args args, ServerState server) {

    /**
     * @return the database currently selected by the client
     */
    public Database db() {
        return server.databases().get(client.db());
    }
}
