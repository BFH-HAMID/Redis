package dev.hamid.redisj.cluster;

import java.util.List;
import java.util.Optional;

/**
 * Decides whether a command's keys can be served by this node.
 */
public final class ClusterRouting {

    private ClusterRouting() {
    }

    /**
     * @param state the cluster map
     * @param keys  the keys the command touches
     * @return an error reply if the command cannot run here, or empty if it can
     */
    public static Optional<String> check(ClusterState state, List<String> keys) {
        if (keys.isEmpty()) {
            return Optional.empty();
        }
        int slot = KeySlot.of(keys.get(0));
        for (String key : keys) {
            if (KeySlot.of(key) != slot) {
                return Optional.of("CROSSSLOT Keys in request don't hash to the same slot");
            }
        }
        if (!state.isMine(slot)) {
            ClusterState.Node owner = state.ownerOf(slot);
            return Optional.of("MOVED " + slot + " " + owner.address());
        }
        return Optional.empty();
    }
}
