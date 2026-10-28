package dev.hamid.redisj.command.handlers;

import dev.hamid.redisj.cluster.ClusterState;
import dev.hamid.redisj.cluster.KeySlot;
import dev.hamid.redisj.command.Args;
import dev.hamid.redisj.command.Command;
import dev.hamid.redisj.command.CommandContext;
import dev.hamid.redisj.command.CommandException;
import dev.hamid.redisj.command.CommandRegistry;
import dev.hamid.redisj.command.Replies;
import dev.hamid.redisj.protocol.RespValue;
import java.util.Set;

/**
 * The {@code CLUSTER} command: {@code KEYSLOT}, {@code MYID} and {@code INFO}.
 */
public final class ClusterCommands {

    private static final Set<Command.Flag> READ = Set.of(Command.Flag.READONLY);

    private ClusterCommands() {
    }

    /**
     * Registers {@code CLUSTER}.
     *
     * @param registry the registry to add to
     */
    public static void register(CommandRegistry registry) {
        registry.register(Command.of("CLUSTER", -2, READ, 0, 0, 0, ClusterCommands::cluster));
    }

    static RespValue cluster(CommandContext ctx) {
        Args a = ctx.args();
        String sub = a.upper(1);
        if (sub.equals("KEYSLOT") && a.size() == 3) {
            return Replies.integer(KeySlot.of(a.key(2)));
        }
        if (sub.equals("MYID") && a.size() == 2) {
            return Replies.bulkText(requireCluster(ctx).myId());
        }
        if (sub.equals("INFO") && a.size() == 2) {
            ClusterState map = requireCluster(ctx);
            int assigned = KeySlot.SLOTS;
            String info = "cluster_enabled:1\r\ncluster_state:ok\r\ncluster_known_nodes:"
                    + map.nodeCount() + "\r\ncluster_slots_assigned:" + assigned + "\r\n";
            return Replies.bulkText(info);
        }
        throw new CommandException("ERR unknown subcommand or wrong number of arguments for '"
                + a.text(1) + "'. Try CLUSTER HELP.");
    }

    private static ClusterState requireCluster(CommandContext ctx) {
        return ctx.server().cluster().orElseThrow(
                () -> new CommandException("ERR This instance has cluster support disabled"));
    }
}
