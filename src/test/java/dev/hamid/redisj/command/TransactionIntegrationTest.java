package dev.hamid.redisj.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hamid.redisj.server.RedisServer;
import dev.hamid.redisj.server.ServerOptions;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Response;
import redis.clients.jedis.Transaction;

/**
 * Phase 6 tests: MULTI/EXEC/DISCARD queuing, error semantics, and WATCH optimistic locking.
 */
@Timeout(60)
class TransactionIntegrationTest {

    private RedisServer server;
    private Jedis jedis;
    private Socket rawSocket;
    private OutputStream rawOut;
    private InputStream rawIn;

    @BeforeEach
    void start() throws IOException {
        ServerState state = new ServerState(16, null, System::currentTimeMillis);
        server = new RedisServer(ServerOptions.ephemeral(), CommandRegistry.withDefaults(state));
        state.attachClients(server::clients);
        server.start();
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
    void multiQueuesCommandsAndExecReturnsRepliesInOrder() {
        Transaction tx = jedis.multi();
        tx.set("a", "1");
        Response<Long> incr = tx.incr("a");
        Response<String> get = tx.get("a");
        List<Object> results = tx.exec();
        assertEquals(3, results.size());
        assertEquals(2L, incr.get());
        assertEquals("2", get.get());
        assertEquals("2", jedis.get("a"));
    }

    @Test
    void discardThrowsAwayQueuedCommands() {
        Transaction tx = jedis.multi();
        tx.set("d", "gone");
        tx.discard();
        assertNull(jedis.get("d"));
    }

    @Test
    void execWithoutMultiIsAnError() throws IOException {
        assertEquals("-ERR EXEC without MULTI", raw("EXEC"));
        assertEquals("-ERR DISCARD without MULTI", raw("DISCARD"));
    }

    @Test
    void nestedMultiIsAnError() throws IOException {
        assertEquals("+OK", raw("MULTI"));
        assertEquals("-ERR MULTI calls can not be nested", raw("MULTI"));
    }

    @Test
    void rejectedCommandWhileQueuingAbortsExec() throws IOException {
        assertEquals("+OK", raw("MULTI"));
        assertTrue(raw("NOSUCH").startsWith("-ERR unknown command 'NOSUCH'"));
        assertEquals("-EXECABORT Transaction discarded because of previous errors.", raw("EXEC"));
        assertEquals("-ERR EXEC without MULTI", raw("EXEC"));
    }

    @Test
    void wrongArityWhileQueuingAbortsExec() throws IOException {
        assertEquals("+OK", raw("MULTI"));
        assertEquals("-ERR wrong number of arguments for 'get' command", raw("GET"));
        assertTrue(raw("EXEC").startsWith("-EXECABORT"));
    }

    @Test
    void runtimeErrorDoesNotAbortOtherCommands() throws IOException {
        assertEquals("+OK", raw("MULTI"));
        assertEquals("+QUEUED", raw("SET", "s", "v"));
        assertEquals("+QUEUED", raw("INCR", "s"));
        assertEquals("+QUEUED", raw("SET", "after", "ok"));
        assertEquals("*3", raw("EXEC"));
        assertEquals("+OK", readLine());
        assertEquals("-ERR value is not an integer or out of range", readLine());
        assertEquals("+OK", readLine());
        assertEquals("ok", jedis.get("after"));
    }

    @Test
    void watchedKeyModifiedByAnotherClientAbortsExec() {
        jedis.set("w", "1");
        jedis.watch("w");
        try (Jedis other = new Jedis("127.0.0.1", server.port())) {
            other.set("w", "2");
        }
        Transaction tx = jedis.multi();
        tx.set("w", "mine");
        assertNull(tx.exec());
        assertEquals("2", jedis.get("w"));
    }

    @Test
    void unchangedWatchedKeyAllowsExec() {
        jedis.set("w", "1");
        jedis.watch("w");
        Transaction tx = jedis.multi();
        tx.set("w", "mine");
        assertNotNull(tx.exec());
        assertEquals("mine", jedis.get("w"));
    }

    @Test
    void unwatchReleasesTheWatch() {
        jedis.set("w", "1");
        jedis.watch("w");
        try (Jedis other = new Jedis("127.0.0.1", server.port())) {
            other.set("w", "2");
        }
        jedis.unwatch();
        Transaction tx = jedis.multi();
        tx.set("w", "mine");
        assertNotNull(tx.exec());
    }

    @Test
    void flushAllInvalidatesWatches() {
        jedis.set("w", "1");
        jedis.watch("w");
        try (Jedis other = new Jedis("127.0.0.1", server.port())) {
            other.flushAll();
        }
        Transaction tx = jedis.multi();
        tx.set("w", "mine");
        assertNull(tx.exec());
    }

    @Test
    void watchInsideMultiIsRejected() throws IOException {
        assertEquals("+OK", raw("MULTI"));
        assertEquals("-ERR WATCH inside MULTI is not allowed", raw("WATCH", "x"));
    }
}
