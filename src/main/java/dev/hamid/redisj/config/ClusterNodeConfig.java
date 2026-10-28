package dev.hamid.redisj.config;

/**
 * One {@code cluster-node} directive: a node and the inclusive slot range it owns.
 *
 * @param id       the node's name
 * @param host     the host clients connect to
 * @param port     the port clients connect to
 * @param fromSlot the first slot owned, inclusive
 * @param toSlot   the last slot owned, inclusive
 */
public record ClusterNodeConfig(String id, String host, int port, int fromSlot, int toSlot) {
}
