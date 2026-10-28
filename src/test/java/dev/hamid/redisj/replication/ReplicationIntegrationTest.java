package dev.hamid.redisj.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hamid.redisj.command.CommandRegistry;
import dev.hamid.redisj.command.ServerState;
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

@Timeout(60)
class ReplicationIntegrationTest {

    private RedisServer leader;
    private RedisServer replica;
    private Jedis leaderJedis;
    private Jedis replicaJedis;

    @BeforeEach
    void start() throws IOException {
        leader = start(new ServerState(16, null, System::currentTimeMillis));
        ServerState replicaState = new ServerState(16, null, System::currentTimeMillis);
        replica = start(replicaState);
        leaderJedis = new Jedis("127.0.0.1", leader.port());
        replicaJedis = new Jedis("127.0.0.1", replica.port());
        leaderJedis.flushAll();
    }

    private static RedisServer start(ServerState state) throws IOException {
        RedisServer server = new RedisServer(ServerOptions.ephemeral(),
                CommandRegistry.withDefaults(state));
        state.attachClients(server::clients);
        server.start();
        return server;
    }

    @AfterEach
    void stop() {
        leaderJedis.close();
        replicaJedis.close();
        replica.close();
        leader.close();
    }

    @Test
    void replicaLoadsTheMastersDatasetAndRejectsWrites() {
        leaderJedis.set("greeting", "hello");
        leaderJedis.rpush("list", "a", "b");
        replicaJedis.replicaof("127.0.0.1", leader.port());

        assertEquals("hello", replicaJedis.get("greeting"));
        assertEquals(List.of("a", "b"), replicaJedis.lrange("list", 0, -1));
        JedisDataException refused = assertThrows(JedisDataException.class,
                () -> replicaJedis.set("local", "x"));
        assertTrue(refused.getMessage().startsWith("READONLY"), refused.getMessage());
    }

    @Test
    void replicaofNoOneMakesTheReplicaWritableAgain() {
        leaderJedis.set("k", "v");
        replicaJedis.replicaof("127.0.0.1", leader.port());
        replicaJedis.replicaofNoOne();
        assertEquals("OK", replicaJedis.set("local", "x"));
        assertEquals("x", replicaJedis.get("local"));
    }

    @Test
    void failedSyncLeavesTheReplicaWritable() {
        JedisDataException failure = assertThrows(JedisDataException.class,
                () -> replicaJedis.replicaof("127.0.0.1", 1));
        assertTrue(failure.getMessage().startsWith("ERR"), failure.getMessage());
        assertEquals("OK", replicaJedis.set("local", "x"));
    }
}
