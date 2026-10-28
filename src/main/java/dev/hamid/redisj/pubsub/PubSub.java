package dev.hamid.redisj.pubsub;

import dev.hamid.redisj.protocol.RespValue;
import dev.hamid.redisj.server.ClientConnection;
import dev.hamid.redisj.store.GlobMatcher;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The publish/subscribe registry: which connections subscribe to which channels and patterns,
 * and delivery of published messages.
 *
 * <p>Channel and pattern names are ISO-8859-1 strings, the same mapping keys use, so arbitrary
 * bytes round-trip. Messages are delivered as RESP2 arrays: {@code ["message", channel, payload]}
 * for channel subscribers and {@code ["pmessage", pattern, channel, payload]} for pattern
 * subscribers.
 *
 * <p>Used only from the execution loop, so it needs no locking. Replies are queued through
 * {@link ClientConnection#reply(RespValue)}, which is safe from that loop.
 */
public final class PubSub {

    /** Per-connection subscription sets. */
    private static final class Subscriptions {
        private final Set<String> channels = new LinkedHashSet<>();
        private final Set<String> patterns = new LinkedHashSet<>();

        int total() {
            return channels.size() + patterns.size();
        }
    }

    private final Map<String, Set<ClientConnection>> channelSubscribers = new HashMap<>();
    private final Map<String, Set<ClientConnection>> patternSubscribers = new HashMap<>();
    private final Map<ClientConnection, Subscriptions> byClient = new HashMap<>();

    /**
     * @param client the connection
     * @return {@code true} if the connection holds at least one channel or pattern subscription
     */
    public boolean isSubscribed(ClientConnection client) {
        Subscriptions subs = byClient.get(client);
        return subs != null && subs.total() > 0;
    }

    /**
     * Subscribes a connection to a channel. Subscribing twice is a no-op.
     *
     * @param client  the subscriber
     * @param channel the channel name
     * @return the connection's total subscription count afterwards
     */
    public int subscribe(ClientConnection client, String channel) {
        Subscriptions subs = byClient.computeIfAbsent(client, c -> new Subscriptions());
        if (subs.channels.add(channel)) {
            channelSubscribers.computeIfAbsent(channel, c -> new LinkedHashSet<>()).add(client);
        }
        return subs.total();
    }

    /**
     * Removes a channel subscription. Unknown channels are ignored.
     *
     * @param client  the subscriber
     * @param channel the channel name
     * @return the connection's total subscription count afterwards
     */
    public int unsubscribe(ClientConnection client, String channel) {
        Subscriptions subs = byClient.get(client);
        if (subs == null) {
            return 0;
        }
        if (subs.channels.remove(channel)) {
            removeFrom(channelSubscribers, channel, client);
        }
        dropIfEmpty(client, subs);
        return subs.total();
    }

    /**
     * Subscribes a connection to a glob pattern. Subscribing twice is a no-op.
     *
     * @param client  the subscriber
     * @param pattern the glob pattern
     * @return the connection's total subscription count afterwards
     */
    public int psubscribe(ClientConnection client, String pattern) {
        Subscriptions subs = byClient.computeIfAbsent(client, c -> new Subscriptions());
        if (subs.patterns.add(pattern)) {
            patternSubscribers.computeIfAbsent(pattern, c -> new LinkedHashSet<>()).add(client);
        }
        return subs.total();
    }

    /**
     * Removes a pattern subscription. Unknown patterns are ignored.
     *
     * @param client  the subscriber
     * @param pattern the glob pattern
     * @return the connection's total subscription count afterwards
     */
    public int punsubscribe(ClientConnection client, String pattern) {
        Subscriptions subs = byClient.get(client);
        if (subs == null) {
            return 0;
        }
        if (subs.patterns.remove(pattern)) {
            removeFrom(patternSubscribers, pattern, client);
        }
        dropIfEmpty(client, subs);
        return subs.total();
    }

    /**
     * @param client the subscriber
     * @return the channels the connection is subscribed to, in subscription order
     */
    public List<String> channelsOf(ClientConnection client) {
        Subscriptions subs = byClient.get(client);
        return subs == null ? List.of() : List.copyOf(subs.channels);
    }

    /**
     * @param client the subscriber
     * @return the patterns the connection is subscribed to, in subscription order
     */
    public List<String> patternsOf(ClientConnection client) {
        Subscriptions subs = byClient.get(client);
        return subs == null ? List.of() : List.copyOf(subs.patterns);
    }

    /**
     * Removes every subscription a connection holds. Called when it disconnects.
     *
     * @param client the connection that closed
     */
    public void releaseClient(ClientConnection client) {
        Subscriptions subs = byClient.remove(client);
        if (subs == null) {
            return;
        }
        for (String channel : subs.channels) {
            removeFrom(channelSubscribers, channel, client);
        }
        for (String pattern : subs.patterns) {
            removeFrom(patternSubscribers, pattern, client);
        }
    }

    /**
     * Delivers a message to every matching subscriber.
     *
     * @param channel the channel published to
     * @param message the payload bytes
     * @return the number of deliveries, which is the value {@code PUBLISH} returns
     */
    public int publish(String channel, byte[] message) {
        int receivers = 0;
        Set<ClientConnection> direct = channelSubscribers.get(channel);
        if (direct != null) {
            RespValue payload = array(bulk("message"), bulk(channel), bulkBytes(message));
            for (ClientConnection client : List.copyOf(direct)) {
                client.reply(payload);
                receivers++;
            }
        }
        for (Map.Entry<String, Set<ClientConnection>> entry : patternSubscribers.entrySet()) {
            String pattern = entry.getKey();
            if (!GlobMatcher.matches(pattern, channel)) {
                continue;
            }
            RespValue payload = array(bulk("pmessage"), bulk(pattern), bulk(channel),
                    bulkBytes(message));
            for (ClientConnection client : List.copyOf(entry.getValue())) {
                client.reply(payload);
                receivers++;
            }
        }
        return receivers;
    }

    /**
     * @param pattern a glob pattern, or {@code null} for all channels
     * @return the channels that have at least one subscriber and match the pattern, sorted
     */
    public List<String> activeChannels(String pattern) {
        TreeSet<String> names = new TreeSet<>();
        for (Map.Entry<String, Set<ClientConnection>> entry : channelSubscribers.entrySet()) {
            if (!entry.getValue().isEmpty()
                    && (pattern == null || GlobMatcher.matches(pattern, entry.getKey()))) {
                names.add(entry.getKey());
            }
        }
        return new ArrayList<>(names);
    }

    /**
     * @param client the connection
     * @return the connection's total subscription count (channels and patterns)
     */
    public int totalFor(ClientConnection client) {
        Subscriptions subs = byClient.get(client);
        return subs == null ? 0 : subs.total();
    }

    /**
     * @param channel the channel
     * @return the number of channel subscribers (not pattern subscribers)
     */
    public int subscriberCount(String channel) {
        Set<ClientConnection> set = channelSubscribers.get(channel);
        return set == null ? 0 : set.size();
    }

    /**
     * @return the number of pattern subscriptions across all connections
     */
    public int patternCount() {
        int total = 0;
        for (Set<ClientConnection> set : patternSubscribers.values()) {
            total += set.size();
        }
        return total;
    }

    private void dropIfEmpty(ClientConnection client, Subscriptions subs) {
        if (subs.total() == 0) {
            byClient.remove(client);
        }
    }

    private static void removeFrom(Map<String, Set<ClientConnection>> index, String name,
                                   ClientConnection client) {
        Set<ClientConnection> set = index.get(name);
        if (set != null) {
            set.remove(client);
            if (set.isEmpty()) {
                index.remove(name);
            }
        }
    }

    private static RespValue bulk(String text) {
        return bulkBytes(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static RespValue bulkBytes(byte[] bytes) {
        return new RespValue.BulkString(bytes);
    }

    private static RespValue array(RespValue... items) {
        return new RespValue.Array(List.of(items));
    }
}
