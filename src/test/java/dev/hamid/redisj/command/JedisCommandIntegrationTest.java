package dev.hamid.redisj.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hamid.redisj.server.RedisServer;
import dev.hamid.redisj.server.ServerOptions;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;
import redis.clients.jedis.SetParams;
import redis.clients.jedis.exceptions.JedisDataException;

/**
 * End-to-end tests of the Phase 4 command set, driven through the Jedis client against an
 * in-process server on an ephemeral port. Option combinations that need exact control over the
 * wire format use a small raw socket helper instead.
 */
@Timeout(60)
class JedisCommandIntegrationTest {

    private RedisServer server;
    private Jedis jedis;
    private Socket rawSocket;
    private OutputStream rawOut;
    private InputStream rawIn;

    @BeforeEach
    void start() throws IOException {
        server = startServer(ServerOptions.ephemeral(), null);
        jedis = new Jedis("127.0.0.1", server.port());
        rawSocket = new Socket("127.0.0.1", server.port());
        rawOut = rawSocket.getOutputStream();
        rawIn = new BufferedInputStream(rawSocket.getInputStream());
        jedis.flushAll();
    }

    @AfterEach
    void stop() throws IOException {
        rawSocket.close();
        jedis.close();
        server.close();
    }

    private static RedisServer startServer(ServerOptions options, String password) throws IOException {
        ServerState state = new ServerState(16, password, System::currentTimeMillis);
        RedisServer started = new RedisServer(options, CommandRegistry.withDefaults(state));
        state.attachClients(started::clients);
        started.start();
        return started;
    }

    /** Sends one command as a RESP array and returns the first reply line, without CRLF. */
    private String raw(String... args) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length).append("\r\n");
        for (String arg : args) {
            int length = arg.getBytes(StandardCharsets.UTF_8).length;
            sb.append('$').append(length).append("\r\n").append(arg).append("\r\n");
        }
        rawOut.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        rawOut.flush();
        return readLine();
    }

    private String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = rawIn.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                line.append((char) c);
            }
        }
        return line.toString();
    }

    @Test
    void pingEchoAndUnknownCommand() throws IOException {
        assertEquals("PONG", jedis.ping());
        assertEquals("hi", jedis.echo("hi"));
        assertEquals("-ERR unknown command 'NOSUCH', with args beginning with: 'x' ",
                raw("NOSUCH", "x"));
    }

    @Test
    void wrongArityIsReported() throws IOException {
        assertEquals("-ERR wrong number of arguments for 'get' command", raw("GET"));
        assertEquals("-ERR wrong number of arguments for 'mset' command", raw("MSET", "a", "1", "b"));
    }

    @Test
    void stringBasics() {
        assertEquals("OK", jedis.set("k", "v"));
        assertEquals("v", jedis.get("k"));
        assertNull(jedis.get("missing"));
        assertEquals("v", jedis.getSet("k", "w"));
        assertEquals("OK", jedis.mset("a", "1", "b", "2"));
        assertEquals(Arrays.asList("1", "2", null), jedis.mget("a", "b", "nope"));
        assertEquals(3L, jedis.append("k", "ab").longValue());
        assertEquals(3L, jedis.strlen("k").longValue());
    }

    @Test
    void setConditionsAndExpiry() throws InterruptedException {
        assertNull(jedis.set("n", "1", SetParams.setParams().xx()));
        assertEquals("OK", jedis.set("n", "1", SetParams.setParams().nx().ex(100)));
        assertNull(jedis.set("n", "2", SetParams.setParams().nx()));
        long ttl = jedis.ttl("n");
        assertTrue(ttl > 90 && ttl <= 100, "ttl was " + ttl);

        assertEquals("OK", jedis.set("p", "x", SetParams.setParams().px(100)));
        Thread.sleep(250);
        assertNull(jedis.get("p"));
    }

    @Test
    void integerCounters() {
        assertEquals(1L, jedis.incr("c").longValue());
        assertEquals(11L, jedis.incrBy("c", 10).longValue());
        assertEquals(10L, jedis.decr("c").longValue());
        assertEquals(5L, jedis.decrBy("c", 5).longValue());

        jedis.set("f", "10.5");
        assertEquals(11.0, jedis.incrByFloat("f", 0.5).doubleValue(), 1e-9);

        jedis.set("bad", "abc");
        JedisDataException notInt = assertThrows(JedisDataException.class, () -> jedis.incr("bad"));
        assertTrue(notInt.getMessage().contains("not an integer"));

        jedis.set("max", Long.toString(Long.MAX_VALUE));
        JedisDataException overflow = assertThrows(JedisDataException.class, () -> jedis.incr("max"));
        assertTrue(overflow.getMessage().contains("overflow"));
    }

    @Test
    void getrangeAndSetrange() {
        jedis.set("s", "Hello World");
        assertEquals("Hello", jedis.getrange("s", 0, 4));
        assertEquals("World", jedis.getrange("s", -5, -1));
        assertEquals("", jedis.getrange("s", 20, 30));

        jedis.set("k", "Hello World");
        assertEquals(11L, jedis.setrange("k", 6, "Redis").longValue());
        assertEquals("Hello Redis", jedis.get("k"));

        assertEquals(4L, jedis.setrange("new", 3, "x").longValue());
        assertEquals("\0\0\0x", jedis.get("new"));
    }

    @Test
    void keyCommands() throws IOException {
        jedis.set("a", "1");
        jedis.set("b", "2");
        assertEquals(":2", raw("EXISTS", "a", "b", "zzz"));
        assertEquals("string", jedis.type("a"));
        assertEquals("none", jedis.type("zzz"));

        assertEquals("OK", jedis.rename("a", "c"));
        assertFalse(jedis.exists("a"));
        JedisDataException missing = assertThrows(JedisDataException.class, () -> jedis.rename("a", "x"));
        assertTrue(missing.getMessage().contains("no such key"));

        assertEquals(Set.of("b", "c"), jedis.keys("*"));
        assertEquals(2L, jedis.dbsize().longValue());
        assertEquals(1L, jedis.del("b").longValue());
        assertEquals(1L, jedis.dbsize().longValue());
    }

    @Test
    void expireTtlAndPersist() {
        jedis.set("e", "v");
        assertEquals(-1L, jedis.ttl("e").longValue());
        assertEquals(-2L, jedis.ttl("zz").longValue());
        assertEquals(1L, jedis.expire("e", 100).longValue());
        long ttl = jedis.ttl("e");
        assertTrue(ttl > 90 && ttl <= 100, "ttl was " + ttl);
        assertEquals(1L, jedis.persist("e").longValue());
        assertEquals(-1L, jedis.ttl("e").longValue());

        assertEquals(1L, jedis.expire("e", -1).longValue(), "negative expire deletes the key");
        assertFalse(jedis.exists("e"));
    }

    @Test
    void expireConditionsFollowRedisRules() throws IOException {
        assertEquals(":0", raw("EXPIRE", "x", "100", "XX"), "missing key");
        assertEquals("+OK", raw("SET", "x", "v"));
        assertEquals(":0", raw("EXPIRE", "x", "100", "XX"), "no TTL to replace");
        assertEquals(":1", raw("EXPIRE", "x", "100", "NX"));
        assertEquals(":0", raw("EXPIRE", "x", "50", "NX"));
        assertEquals(":0", raw("EXPIRE", "x", "500", "LT"));
        assertEquals(":1", raw("EXPIRE", "x", "50", "LT"));
        assertEquals(":1", raw("EXPIRE", "x", "500", "GT"));
        assertEquals(":0", raw("EXPIRE", "x", "10", "GT"));
        assertTrue(raw("EXPIRE", "x", "10", "NX", "XX").startsWith("-ERR NX and XX"));
        assertTrue(raw("EXPIRE", "x", "10", "GT", "LT").startsWith("-ERR GT and LT"));
    }

    @Test
    void scanVisitsEveryKeyExactlyOnce() {
        for (int i = 0; i < 250; i++) {
            jedis.set("key:" + i, "v");
        }
        List<String> seen = new ArrayList<>();
        String cursor = "0";
        int rounds = 0;
        do {
            ScanResult<String> page = jedis.scan(cursor, ScanParams.scanParams().count(10));
            seen.addAll(page.getResult());
            cursor = page.getCursor();
            rounds++;
        } while (!"0".equals(cursor) && rounds < 1_000);
        assertEquals(250, seen.size());
        assertEquals(250, new HashSet<>(seen).size());
    }

    @Test
    void scanMatchFiltersKeys() {
        jedis.set("user:1", "v");
        jedis.set("user:2", "v");
        jedis.set("post:1", "v");
        ScanResult<String> page = jedis.scan("0", ScanParams.scanParams().match("user:*").count(100));
        assertEquals("0", page.getCursor());
        assertEquals(Set.of("user:1", "user:2"), new HashSet<>(page.getResult()));
    }

    @Test
    void databasesAreIsolated() {
        jedis.set("shared", "zero");
        jedis.select(1);
        assertNull(jedis.get("shared"));
        jedis.set("shared", "one");
        jedis.select(0);
        assertEquals("zero", jedis.get("shared"));
        assertThrows(JedisDataException.class, () -> jedis.select(16));
    }

    @Test
    void flushDbAndFlushAll() {
        jedis.set("a", "1");
        jedis.flushDB();
        assertEquals(0L, jedis.dbsize().longValue());
        jedis.set("b", "2");
        jedis.flushAll();
        assertEquals(0L, jedis.dbsize().longValue());
    }

    @Test
    void requirePassGatesCommandsUntilAuth() throws IOException {
        ServerOptions base = ServerOptions.ephemeral();
        ServerOptions secured = new ServerOptions(base.address(), base.ioThreads(), base.maxClients(),
                base.idleTimeoutSeconds(), "secret", base.outputHighWaterBytes(), base.outputHardLimitBytes(),
                base.readBufferBytes());
        try (RedisServer secure = startServer(secured, "secret");
             Jedis client = new Jedis("127.0.0.1", secure.port())) {
            JedisDataException denied = assertThrows(JedisDataException.class, () -> client.get("x"));
            assertTrue(denied.getMessage().startsWith("NOAUTH"));
            assertThrows(JedisDataException.class, () -> client.auth("wrong"));
            assertEquals("OK", client.auth("secret"));
            assertEquals("OK", client.set("x", "v"));
            assertEquals("v", client.get("x"));
        }
    }
}
