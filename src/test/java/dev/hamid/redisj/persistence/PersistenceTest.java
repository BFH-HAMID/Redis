package dev.hamid.redisj.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hamid.redisj.command.CommandRegistry;
import dev.hamid.redisj.command.ServerState;
import dev.hamid.redisj.protocol.RespValue;
import dev.hamid.redisj.server.ClientConnection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistenceTest {

    @TempDir
    Path dir;

    /** One server instance over the test directory, driven through the registry directly. */
    private static final class Node implements AutoCloseable {
        private final CommandRegistry registry;
        private final Persistence persistence;
        private final ClientConnection client;

        Node(Path dir, boolean appendOnly) throws IOException {
            ServerState state = new ServerState(16, null, System::currentTimeMillis);
            registry = CommandRegistry.withDefaults(state);
            persistence = Persistence.open(state, registry, dir, appendOnly);
            client = ClientConnection.detached(1);
        }

        RespValue run(String... parts) {
            List<RespValue> elements = new ArrayList<>(parts.length);
            for (String part : parts) {
                elements.add(new RespValue.BulkString(part.getBytes(StandardCharsets.UTF_8)));
            }
            return registry.handle(client, new RespValue.Array(elements));
        }

        @Override
        public void close() {
            persistence.close();
        }
    }

    private static String text(RespValue value) {
        if (value instanceof RespValue.BulkString bulk) {
            return new String(bulk.bytes(), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static long integer(RespValue value) {
        return ((RespValue.Integer) value).value();
    }

    @Test
    void appendOnlyFileRoundTripsAllTypes() throws IOException {
        try (Node node = new Node(dir, true)) {
            node.run("SET", "a", "1");
            node.run("RPUSH", "l", "x", "y");
            node.run("HSET", "h", "f", "v");
            node.run("SADD", "s", "m");
        }
        try (Node node = new Node(dir, true)) {
            assertEquals("1", text(node.run("GET", "a")));
            assertEquals(2, integer(node.run("LLEN", "l")));
            assertEquals("v", text(node.run("HGET", "h", "f")));
            assertEquals(1, integer(node.run("SCARD", "s")));
        }
    }

    @Test
    void selectIsReplayedPerDatabase() throws IOException {
        try (Node node = new Node(dir, true)) {
            node.run("SELECT", "2");
            node.run("SET", "k", "v");
        }
        try (Node node = new Node(dir, true)) {
            assertNull(text(node.run("GET", "k")));
            node.run("SELECT", "2");
            assertEquals("v", text(node.run("GET", "k")));
        }
    }

    @Test
    void expiryIsAbsoluteAcrossRestart() throws Exception {
        try (Node node = new Node(dir, true)) {
            node.run("SET", "short", "v", "PX", "1000");
            node.run("SET", "long", "v", "PX", "60000");
        }
        Thread.sleep(1200);
        try (Node node = new Node(dir, true)) {
            assertNull(text(node.run("GET", "short")));
            long ttl = integer(node.run("PTTL", "long"));
            assertTrue(ttl > 0 && ttl <= 60000, "ttl was " + ttl);
        }
    }

    @Test
    void truncatedTailIsDiscardedAndFileTrimmed() throws IOException {
        try (Node node = new Node(dir, true)) {
            node.run("SET", "a", "1");
        }
        Path aof = dir.resolve(Persistence.AOF_FILE);
        long goodSize = Files.size(aof);
        Files.write(aof, "*3\r\n$3\r\nSET\r\n$1".getBytes(StandardCharsets.US_ASCII),
                java.nio.file.StandardOpenOption.APPEND);
        try (Node node = new Node(dir, true)) {
            assertEquals("1", text(node.run("GET", "a")));
        }
        assertEquals(goodSize, Files.size(aof));
    }

    @Test
    void saveWritesSnapshotWhenAppendOnlyIsOff() throws IOException {
        try (Node node = new Node(dir, false)) {
            node.run("SET", "a", "1");
            node.run("SELECT", "3");
            node.run("HSET", "h", "f", "v");
            assertEquals("OK", ((RespValue.SimpleString) node.run("SAVE")).value());
        }
        assertFalse(Files.exists(dir.resolve(Persistence.AOF_FILE)));
        try (Node node = new Node(dir, false)) {
            assertEquals("1", text(node.run("GET", "a")));
            node.run("SELECT", "3");
            assertEquals("v", text(node.run("HGET", "h", "f")));
        }
    }

    @Test
    void saveRewritesAppendOnlyFileAndKeepsLoggingAfterwards() throws IOException {
        try (Node node = new Node(dir, true)) {
            node.run("SET", "a", "1");
            node.run("SET", "a", "2");
            node.run("SET", "b", "3");
            node.run("DEL", "b");
            node.run("SELECT", "1");
            node.run("SET", "x", "y");
            node.run("SAVE");
            node.run("SET", "z", "w");
        }
        try (Node node = new Node(dir, true)) {
            assertEquals("2", text(node.run("GET", "a")));
            assertNull(text(node.run("GET", "b")));
            node.run("SELECT", "1");
            assertEquals("y", text(node.run("GET", "x")));
            assertEquals("w", text(node.run("GET", "z")));
        }
    }

    @Test
    void autoGeneratedStreamIdIsLoggedAsAssigned() throws IOException {
        String id;
        try (Node node = new Node(dir, true)) {
            id = text(node.run("XADD", "s", "*", "f", "v"));
        }
        try (Node node = new Node(dir, true)) {
            RespValue range = node.run("XRANGE", "s", "-", "+");
            RespValue first = ((RespValue.Array) ((RespValue.Array) range).elements().get(0)).elements().get(0);
            assertEquals(id, text(first));
        }
    }
}
