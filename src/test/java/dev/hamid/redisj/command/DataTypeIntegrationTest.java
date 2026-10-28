package dev.hamid.redisj.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hamid.redisj.server.RedisServer;
import dev.hamid.redisj.server.ServerOptions;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisDataException;

/**
 * End-to-end tests of the Phase 5 data structures (lists, hashes and sets) through Jedis.
 */
@Timeout(60)
class DataTypeIntegrationTest {

    private RedisServer server;
    private Jedis jedis;

    @BeforeEach
    void start() throws IOException {
        ServerState state = new ServerState(16, null, System::currentTimeMillis);
        server = new RedisServer(ServerOptions.ephemeral(), CommandRegistry.withDefaults(state));
        state.attachClients(server::clients);
        server.start();
        jedis = new Jedis("127.0.0.1", server.port());
        jedis.flushAll();
    }

    @AfterEach
    void stop() {
        jedis.close();
        server.close();
    }

    @Test
    void listPushPopRangeAndIndex() {
        assertEquals(3L, jedis.rpush("l", "a", "b", "c").longValue());
        assertEquals(4L, jedis.lpush("l", "z").longValue());
        assertEquals(List.of("z", "a", "b", "c"), jedis.lrange("l", 0, -1));
        assertEquals(List.of("b", "c"), jedis.lrange("l", -2, -1));
        assertEquals(List.of(), jedis.lrange("l", 5, 10));
        assertEquals("c", jedis.lindex("l", -1));
        assertNull(jedis.lindex("l", 10));
        assertEquals("z", jedis.lpop("l"));
        assertEquals(List.of("c", "b"), jedis.rpop("l", 2));
        assertEquals(1L, jedis.llen("l").longValue());
        assertEquals("a", jedis.rpop("l"));
    }

    @Test
    void emptyListIsRemovedFromKeyspace() {
        jedis.rpush("l", "only");
        assertEquals("only", jedis.lpop("l"));
        assertEquals("none", jedis.type("l"));
        assertFalse(jedis.exists("l"));
    }

    @Test
    void hashFieldOperations() {
        assertEquals(1L, jedis.hset("h", "f1", "v1").longValue());
        Map<String, String> more = new HashMap<>();
        more.put("f2", "v2");
        more.put("f3", "v3");
        assertEquals(2L, jedis.hset("h", more).longValue());
        assertEquals("v1", jedis.hget("h", "f1"));
        assertNull(jedis.hget("h", "missing"));
        assertTrue(jedis.hexists("h", "f2"));
        assertFalse(jedis.hexists("h", "missing"));
        assertEquals(3L, jedis.hlen("h").longValue());
        assertEquals(Map.of("f1", "v1", "f2", "v2", "f3", "v3"), jedis.hgetAll("h"));
        assertEquals(Set.of("f1", "f2", "f3"), jedis.hkeys("h"));
        assertEquals(3, jedis.hvals("h").size());
        assertEquals(1L, jedis.hdel("h", "f1").longValue());
        assertEquals(0L, jedis.hset("h", "f2", "updated").longValue(), "updating does not add a field");
        assertEquals("updated", jedis.hget("h", "f2"));
    }

    @Test
    void emptyHashIsRemovedFromKeyspace() {
        jedis.hset("h", "f", "v");
        assertEquals(1L, jedis.hdel("h", "f").longValue());
        assertEquals("none", jedis.type("h"));
    }

    @Test
    void setMembership() {
        assertEquals(3L, jedis.sadd("s", "a", "b", "c").longValue());
        assertEquals(0L, jedis.sadd("s", "a").longValue());
        assertTrue(jedis.sismember("s", "b"));
        assertFalse(jedis.sismember("s", "z"));
        assertEquals(Set.of("a", "b", "c"), jedis.smembers("s"));
        assertEquals(1L, jedis.srem("s", "a").longValue());
        assertEquals(2L, jedis.scard("s").longValue());
    }

    @Test
    void emptySetIsRemovedFromKeyspace() {
        jedis.sadd("s", "x");
        assertEquals(1L, jedis.srem("s", "x").longValue());
        assertEquals("none", jedis.type("s"));
    }

    @Test
    void typeReportsEachCollectionKind() {
        jedis.rpush("l", "x");
        jedis.hset("h", "f", "v");
        jedis.sadd("s", "m");
        jedis.set("str", "v");
        assertEquals("list", jedis.type("l"));
        assertEquals("hash", jedis.type("h"));
        assertEquals("set", jedis.type("s"));
        assertEquals("string", jedis.type("str"));
    }

    @Test
    void wrongTypeErrorsAcrossKinds() {
        jedis.set("str", "v");
        jedis.rpush("l", "x");
        jedis.hset("h", "f", "v");
        jedis.sadd("s", "m");

        assertWrongType(() -> jedis.lpush("str", "x"));
        assertWrongType(() -> jedis.hget("l", "f"));
        assertWrongType(() -> jedis.sadd("h", "m"));
        assertWrongType(() -> jedis.llen("s"));
        assertWrongType(() -> jedis.get("l"));
        assertWrongType(() -> jedis.hgetAll("s"));
    }

    @Test
    void deleteWorksAcrossKinds() {
        jedis.rpush("l", "x");
        jedis.hset("h", "f", "v");
        jedis.sadd("s", "m");
        assertEquals(3L, jedis.del("l", "h", "s").longValue());
        assertEquals(List.of(), jedis.lrange("l", 0, -1));
    }

    private static void assertWrongType(Runnable action) {
        JedisDataException error = assertThrows(JedisDataException.class, action::run);
        assertTrue(error.getMessage().startsWith("WRONGTYPE"), error.getMessage());
    }
}
