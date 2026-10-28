package dev.hamid.redisj.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.hamid.redisj.command.CommandRegistry;
import dev.hamid.redisj.command.ServerState;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class RedisServerTest {

    private final List<RedisServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        for (RedisServer server : servers) {
            server.close();
        }
        servers.clear();
    }

    private static CommandRegistry registry() {
        return CommandRegistry.withDefaults(new ServerState(16, null, System::currentTimeMillis));
    }

    private RedisServer start(ServerOptions options) throws IOException {
        RedisServer server = new RedisServer(options, registry());
        servers.add(server);
        server.start();
        return server;
    }

    private RedisServer start() throws IOException {
        return start(ServerOptions.ephemeral());
    }

    @Test
    void pingOverRawSocketReturnsPong() throws Exception {
        RedisServer server = start();
        try (RawClient client = new RawClient(server.port())) {
            client.send("*1\r\n$4\r\nPING\r\n");
            assertEquals("+PONG\r\n", client.readText(7));
        }
    }

    @Test
    void inlinePingAndPipelinedCommandsKeepOrder() throws Exception {
        RedisServer server = start();
        try (RawClient client = new RawClient(server.port())) {
            client.send("PING\r\nPING hello\r\nECHO x\r\n");
            assertEquals("+PONG\r\n$5\r\nhello\r\n$1\r\nx\r\n", client.readText(7 + 11 + 7));
        }
    }

    @Test
    void partialFramesAreReassembled() throws Exception {
        RedisServer server = start();
        try (RawClient client = new RawClient(server.port())) {
            String request = "*2\r\n$4\r\nECHO\r\n$5\r\nhello\r\n";
            for (byte b : request.getBytes()) {
                client.sendBytes(new byte[] {b});
            }
            assertEquals("$5\r\nhello\r\n", client.readText(11));
        }
    }

    @Test
    void largeBulkPayloadRoundTrips() throws Exception {
        RedisServer server = start();
        byte[] payload = new byte[1 << 20];
        new Random(7).nextBytes(payload);
        byte[] header = ("*2\r\n$4\r\nECHO\r\n$" + payload.length + "\r\n").getBytes();
        try (RawClient client = new RawClient(server.port())) {
            byte[] request = new byte[header.length + payload.length + 2];
            System.arraycopy(header, 0, request, 0, header.length);
            System.arraycopy(payload, 0, request, header.length, payload.length);
            request[request.length - 2] = '\r';
            request[request.length - 1] = '\n';
            client.sendBytes(request);
            String replyHeader = "$" + payload.length + "\r\n";
            assertEquals(replyHeader, client.readText(replyHeader.length()));
            assertArrayEquals(payload, client.readBytes(payload.length));
            assertEquals("\r\n", client.readText(2));
        }
    }

    @Test
    void errorRepliesDoNotCloseTheConnection() throws Exception {
        RedisServer server = start();
        try (RawClient client = new RawClient(server.port())) {
            String reply = "-ERR unknown command 'NOSUCH', with args beginning with: \r\n";
            client.send("NOSUCH\r\n");
            assertEquals(reply, client.readText(reply.length()));
            client.send("PING\r\n");
            assertEquals("+PONG\r\n", client.readText(7));
        }
    }

    @Test
    void protocolErrorRepliesThenClosesConnection() throws Exception {
        RedisServer server = start();
        try (RawClient client = new RawClient(server.port())) {
            client.send("*1\r\n$x\r\n");
            String reply = "-ERR Protocol error: invalid bulk length\r\n";
            assertEquals(reply, client.readText(reply.length()));
            assertTrue(client.isClosedByPeer());
        }
    }

    @Test
    void quitRepliesOkAndCloses() throws Exception {
        RedisServer server = start();
        try (RawClient client = new RawClient(server.port())) {
            client.send("QUIT\r\n");
            assertEquals("+OK\r\n", client.readText(5));
            assertTrue(client.isClosedByPeer());
        }
    }

    @Test
    void manyConcurrentClientsWithIoThreads() throws Exception {
        RedisServer server = start(ServerOptions.ephemeral().withIoThreads(2));
        int clients = 8;
        int perClient = 100;
        List<Thread> threads = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int c = 0; c < clients; c++) {
            Thread t = new Thread(() -> {
                try (RawClient client = new RawClient(server.port())) {
                    client.send("PING\r\n".repeat(perClient));
                    for (int i = 0; i < perClient; i++) {
                        assertEquals("+PONG\r\n", client.readText(7));
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }
        assertNull(failure.get());
    }

    @Test
    void slowReaderTriggersBackpressureWithoutLosingReplies() throws Exception {
        RedisServer server = start();
        int requests = 300;
        byte[] value = new byte[64 * 1024];
        new Random(3).nextBytes(value);
        byte[] command = concat(("*2\r\n$4\r\nECHO\r\n$" + value.length + "\r\n").getBytes(), value,
                "\r\n".getBytes());
        try (RawClient client = new RawClient(server.port())) {
            AtomicReference<Throwable> writerFailure = new AtomicReference<>();
            Thread writer = new Thread(() -> {
                try {
                    for (int i = 0; i < requests; i++) {
                        client.sendBytes(command);
                    }
                } catch (Throwable e) {
                    writerFailure.set(e);
                }
            });
            writer.start();
            // Do not read yet: the server's output backlog should exceed its high-water mark and pause reads.
            Thread.sleep(500);
            String header = "$" + value.length + "\r\n";
            for (int i = 0; i < requests; i++) {
                assertEquals(header, client.readText(header.length()), "reply " + i);
                assertArrayEquals(value, client.readBytes(value.length), "payload " + i);
                assertEquals("\r\n", client.readText(2));
            }
            writer.join();
            assertNull(writerFailure.get());
        }
    }

    @Test
    void idleClientsAreDisconnected() throws Exception {
        RedisServer server = start(ServerOptions.ephemeral().withIdleTimeoutSeconds(1));
        try (RawClient client = new RawClient(server.port())) {
            assertTrue(client.isClosedByPeer());
        }
    }

    @Test
    void activeClientsAreNotDisconnectedByIdleTimeout() throws Exception {
        RedisServer server = start(ServerOptions.ephemeral().withIdleTimeoutSeconds(1));
        try (RawClient client = new RawClient(server.port())) {
            for (int i = 0; i < 6; i++) {
                client.send("PING\r\n");
                assertEquals("+PONG\r\n", client.readText(7));
                Thread.sleep(300);
            }
        }
    }

    @Test
    void maxClientsRejectsExtraConnections() throws Exception {
        RedisServer server = start(ServerOptions.ephemeral().withMaxClients(1));
        try (RawClient first = new RawClient(server.port())) {
            first.send("PING\r\n");
            assertEquals("+PONG\r\n", first.readText(7));
            try (RawClient second = new RawClient(server.port())) {
                String reply = "-ERR max number of clients reached\r\n";
                assertEquals(reply, second.readText(reply.length()));
                assertTrue(second.isClosedByPeer());
            }
        }
    }

    @Test
    void gracefulShutdownFlushesThenClosesClients() throws Exception {
        RedisServer server = start();
        try (RawClient client = new RawClient(server.port())) {
            client.send("PING\r\n");
            assertEquals("+PONG\r\n", client.readText(7));
            long started = System.currentTimeMillis();
            server.close();
            assertTrue(System.currentTimeMillis() - started < 5_000, "shutdown took too long");
            assertTrue(client.isClosedByPeer());
        }
        assertEquals(0, server.clientCount());
    }

    @Test
    void closeIsIdempotentAndStopsAcceptingConnections() throws Exception {
        RedisServer server = start();
        int port = server.port();
        server.close();
        server.close();
        assertThrows(IOException.class, () -> new RawClient(port));
    }

    @Test
    void bindingAnOccupiedPortFails() throws Exception {
        RedisServer first = start();
        ServerOptions taken = ServerOptions.ephemeral().withIoThreads(0);
        ServerOptions conflicting = new ServerOptions(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), first.port()),
                taken.ioThreads(), taken.maxClients(), 0, null, 256 * 1024, 64L << 20, 16 * 1024);
        assertThrows(IOException.class, () -> new RedisServer(conflicting, registry()));
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, offset, p.length);
            offset += p.length;
        }
        return out;
    }
}
