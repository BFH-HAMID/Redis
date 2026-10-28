package dev.hamid.redisj.pubsub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hamid.redisj.server.ClientConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Bookkeeping of subscriptions and delivery counts; replies are discarded for detached clients. */
class PubSubTest {

    private final PubSub pubsub = new PubSub();
    private final ClientConnection a = ClientConnection.detached(1);
    private final ClientConnection b = ClientConnection.detached(2);

    @Test
    void subscribeCountsChannelsAndIgnoresDuplicates() {
        assertEquals(1, pubsub.subscribe(a, "news"));
        assertEquals(1, pubsub.subscribe(a, "news"));
        assertEquals(2, pubsub.psubscribe(a, "n*"));
        assertTrue(pubsub.isSubscribed(a));
        assertFalse(pubsub.isSubscribed(b));
    }

    @Test
    void publishCountsDirectAndPatternDeliveries() {
        pubsub.subscribe(a, "news");
        pubsub.subscribe(b, "news");
        pubsub.psubscribe(a, "n*");
        assertEquals(3, pubsub.publish("news", "hi".getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, pubsub.publish("sport", "hi".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void unsubscribeRemovesTheLastSubscriptionAndStopsDelivery() {
        pubsub.subscribe(a, "x");
        assertEquals(0, pubsub.unsubscribe(a, "x"));
        assertFalse(pubsub.isSubscribed(a));
        assertEquals(0, pubsub.publish("x", new byte[0]));
    }

    @Test
    void punsubscribeRemovesPatternSubscription() {
        pubsub.psubscribe(a, "p*");
        assertEquals(0, pubsub.punsubscribe(a, "p*"));
        assertEquals(0, pubsub.patternCount());
    }

    @Test
    void releaseClientDropsEverySubscription() {
        pubsub.subscribe(a, "c1");
        pubsub.psubscribe(a, "c*");
        pubsub.subscribe(b, "c1");
        pubsub.releaseClient(a);
        assertFalse(pubsub.isSubscribed(a));
        assertEquals(1, pubsub.subscriberCount("c1"));
        assertEquals(0, pubsub.patternCount());
        assertEquals(1, pubsub.publish("c1", new byte[0]));
    }

    @Test
    void listsActiveChannelsAndCounts() {
        pubsub.subscribe(a, "news.a");
        pubsub.subscribe(b, "news.b");
        pubsub.subscribe(b, "other");
        pubsub.psubscribe(a, "x*");
        assertEquals(List.of("news.a", "news.b"), pubsub.activeChannels("news.*"));
        assertEquals(List.of("news.a", "news.b", "other"), pubsub.activeChannels(null));
        assertEquals(1, pubsub.subscriberCount("news.a"));
        assertEquals(1, pubsub.patternCount());
        assertEquals(List.of("news.a"), pubsub.channelsOf(a));
    }
}
