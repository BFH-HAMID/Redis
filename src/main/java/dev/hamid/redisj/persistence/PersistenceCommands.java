package dev.hamid.redisj.persistence;

import dev.hamid.redisj.command.Command;
import dev.hamid.redisj.command.CommandException;
import dev.hamid.redisj.command.CommandRegistry;
import dev.hamid.redisj.command.Replies;
import dev.hamid.redisj.protocol.RespValue;
import java.io.IOException;
import java.util.Set;

/**
 * The {@code SAVE} command, which writes the dataset to disk.
 */
public final class PersistenceCommands {

    private PersistenceCommands() {
    }

    /**
     * Registers {@code SAVE}.
     *
     * @param registry    the registry to add to
     * @param persistence the persistence handle that performs the save
     */
    public static void register(CommandRegistry registry, Persistence persistence) {
        registry.register(Command.of("SAVE", 1, Set.of(Command.Flag.ADMIN), 0, 0, 0,
                ctx -> save(persistence)));
    }

    static RespValue save(Persistence persistence) {
        try {
            persistence.save();
            return Replies.ok();
        } catch (IOException e) {
            throw new CommandException("ERR save failed: " + e.getMessage());
        }
    }
}
