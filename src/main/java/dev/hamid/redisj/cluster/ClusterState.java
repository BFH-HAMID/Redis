package dev.hamid.redisj.cluster;

import dev.hamid.redisj.config.ClusterNodeConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A static cluster: every node is configured with the same list of nodes and slot ranges, and
 * knows which one it is. Nodes do not talk to each other. Slots are never moved at runtime.
 *
 * <p>The ranges must cover every slot exactly once, so every key has exactly one owner.
 */
public final class ClusterState {

    /**
     * A cluster member.
     *
     * @param id   the node's name
     * @param host the host clients should connect to
     * @param port the port clients should connect to
     */
    public record Node(String id, String host, int port) {

        /** @return {@code host:port} */
        public String address() {
            return host + ":" + port;
        }
    }

    private final String myId;
    private final Node[] owners = new Node[KeySlot.SLOTS];
    private final Map<String, Node> nodes = new HashMap<>();

    private ClusterState(String myId) {
        this.myId = myId;
    }

    /**
     * Builds the cluster map from the configured nodes.
     *
     * @param myId  the id of this node, which must appear in {@code nodes}
     * @param nodes the configured nodes with their slot ranges
     * @return the cluster map
     * @throws IllegalArgumentException if the configuration is inconsistent
     */
    public static ClusterState of(String myId, List<ClusterNodeConfig> nodes) {
        ClusterState state = new ClusterState(myId);
        for (ClusterNodeConfig config : nodes) {
            Node node = new Node(config.id(), config.host(), config.port());
            if (state.nodes.putIfAbsent(node.id(), node) != null) {
                throw new IllegalArgumentException("duplicate cluster node id: " + node.id());
            }
            for (int slot = config.fromSlot(); slot <= config.toSlot(); slot++) {
                if (state.owners[slot] != null) {
                    throw new IllegalArgumentException("slot " + slot + " is assigned twice");
                }
                state.owners[slot] = node;
            }
        }
        if (!state.nodes.containsKey(myId)) {
            throw new IllegalArgumentException("cluster-myself " + myId + " is not a cluster-node");
        }
        for (int slot = 0; slot < KeySlot.SLOTS; slot++) {
            if (state.owners[slot] == null) {
                throw new IllegalArgumentException("slot " + slot + " is not assigned to any node");
            }
        }
        return state;
    }

    /** @return this node's id */
    public String myId() {
        return myId;
    }

    /** @return the node that owns {@code slot} */
    public Node ownerOf(int slot) {
        return owners[slot];
    }

    /** @return {@code true} if this node owns {@code slot} */
    public boolean isMine(int slot) {
        return owners[slot].id().equals(myId);
    }

    /** @return the number of nodes in the cluster */
    public int nodeCount() {
        return nodes.size();
    }
}
