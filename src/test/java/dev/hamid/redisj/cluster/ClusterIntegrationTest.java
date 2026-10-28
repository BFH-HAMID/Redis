package dev.hamid.redisj.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hamid.redisj.command.CommandRegistry;
import dev.hamid.redisj.command.ServerState;
import dev.hamid.redisj.config.ClusterNodeConfig;
import dev.hamid.redisj.server.RedisServer;
import dev.hamid.redisj.server.ServerOptions;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisDataException;

/**
 * One node, {@code a}, owns slots 0-8000. Node {@code b} at port 7001 owns the rest but is not
 * running, so keys in its range must produce MOVED replies.
 */
@Timeout(60)
class ClusterIntegrationTest {

    private static final int A_LAST_SLOT = 8000;

    private RedisServer server;
    private Jedis jedis;

    @BeforeEach
    void start() throws IOException {
        ServerState state = new ServerState(16, null, System::currentTimeMillis);
        state.configureCluster(ClusterState.of("a", List.of(
                new ClusterNodeConfig("a", "127.0.0.1", 7000, 0, A_LAST_SLOT),
                new ClusterNodeConfig("b", "127.0.0.1", 7001, A_LAST_SLOT + 1, KeySlot.SLOTS - 1))));
        server = new RedisServer(ServerOptions.ephemeral(), CommandRegistry.withDefaults(state));
        state.attachClients(server::clients);
        server.start();
        jedis = new Jedis("127.0.0.1", server.port());
    }

    @AfterEach
    void stop() {
        jedis.close();
        server.close();
    }

    /** The first key named key0, key1, ... whose slot is in range and not in the excluded slot. */
    private static String keyInSlots(int from, int to, String avoid) {
        for (int i = 0; ; i++) {
            String key = "key" + i;
            int slot = KeySlot.of(key);
            if (slot >= from && slot <= to && (avoid == null || KeySlot.of(avoid) != slot)) {
                return key;
            }
        }
    }

    @Test
    void localKeysAreServedAndRemoteKeysGetMoved() {
        String mine = keyInSlots(0, A_LAST_SLOT, null);
        String other = keyInSlots(A_LAST_SLOT + 1, KeySlot.SLOTS - 1, null);
        assertEquals("OK", jedis.set(mine, "v"));
        assertEquals("v", jedis.get(mine));
        JedisDataException moved = assertThrows(JedisDataException.class, () -> jedis.set(other, "v"));
        assertTrue(moved.getMessage().startsWith("MOVED " + KeySlot.of(other) + " 127.0.0.1:7001"),
                moved.getMessage());
    }

    @Test
    void multiKeyCommandsMustStayInOneSlot() {
        String first = keyInSlots(0, A_LAST_SLOT, null);
        String second = keyInSlots(0, A_LAST_SLOT, first);
        JedisDataException crossSlot = assertThrows(JedisDataException.class,
                () -> jedis.mset(first, "1", second, "2"));
        assertTrue(crossSlot.getMessage().startsWith("CROSSSLOT"), crossSlot.getMessage());
        // Keys sharing a hash tag hash to the slot of the tag, which this node owns.
        assertEquals("OK", jedis.mset("{" + first + "}x", "1", "{" + first + "}y", "2"));
    }

    @Test
    void keylessCommandsAlwaysRun() {
        assertEquals("PONG", jedis.ping());
    }
}
