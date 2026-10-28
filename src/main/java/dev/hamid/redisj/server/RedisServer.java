package dev.hamid.redisj.server;

import dev.hamid.redisj.protocol.RespValue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The RedisJ TCP server.
 *
 * <p>Threading model:
 * <ul>
 *   <li>The <b>main loop</b> accepts connections, runs the idle sweep and executes every command.
 *       Command execution is single-threaded, so no data structure needs locking.</li>
 *   <li>With {@code io-threads 0} the main loop also reads, parses and writes sockets. With
 *       {@code io-threads N} each client is pinned round-robin to one of N I/O loops, which do
 *       socket work only.</li>
 * </ul>
 * Requests flow I/O loop, then main loop (execute), then I/O loop (write). Per-client order is
 * preserved because all execution happens on one FIFO queue.
 *
 * <p>{@link #close()} performs a graceful shutdown: it stops accepting, closes each client after its
 * pending replies are written, waits up to two seconds, and then stops all loops.
 */
public final class RedisServer implements AutoCloseable, ClientEvents {

    private static final System.Logger LOG = System.getLogger("redisj.server");
    private static final long SHUTDOWN_GRACE_MILLIS = 2_000;
    private static final long JOIN_MILLIS = 2_000;
    private static final int BACKLOG = 511;
    private static final byte[] MAX_CLIENTS_REPLY =
            "-ERR max number of clients reached\r\n".getBytes(StandardCharsets.US_ASCII);

    private final ServerOptions options;
    private final RequestHandler handler;
    private final ServerSocketChannel serverChannel;
    private final EventLoop mainLoop;
    private final EventLoop[] ioLoops;
    private final Map<Long, ClientConnection> clients = new ConcurrentHashMap<>();
    private final AtomicLong nextClientId = new AtomicLong(1);
    private final AtomicInteger nextLoop = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Binds the listening socket and creates the loops. Call {@link #start()} to begin serving.
     *
     * @param options networking options
     * @param handler executes requests on the main loop
     * @throws IOException if the socket cannot be bound or a loop cannot be created
     */
    public RedisServer(ServerOptions options, RequestHandler handler) throws IOException {
        this.options = Objects.requireNonNull(options, "options");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.serverChannel = ServerSocketChannel.open();
        try {
            serverChannel.configureBlocking(false);
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            serverChannel.bind(options.address(), BACKLOG);
        } catch (IOException | RuntimeException e) {
            serverChannel.close();
            throw e;
        }
        this.mainLoop = new EventLoop("redisj-main", this::tick);
        this.ioLoops = buildIoLoops();
        serverChannel.register(mainLoop.selector(), SelectionKey.OP_ACCEPT,
                (SelectHandler) key -> acceptPending());
    }

    /**
     * Starts the main loop and any I/O loops.
     */
    public void start() {
        mainLoop.start();
        for (EventLoop loop : ioLoops) {
            if (loop != mainLoop) {
                loop.start();
            }
        }
    }

    /**
     * @return the TCP port the server is listening on (useful when bound to port 0)
     */
    public int port() {
        try {
            return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @return the number of currently connected clients
     */
    public int clientCount() {
        return clients.size();
    }

    /**
     * Waits for the main loop to terminate, which happens after {@link #close()}.
     *
     * @param millis maximum wait in milliseconds; {@code 0} waits indefinitely
     * @throws InterruptedException if interrupted while waiting
     */
    public void awaitTermination(long millis) throws InterruptedException {
        mainLoop.awaitTermination(millis);
    }

    /**
     * Shuts the server down gracefully. Idempotent.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (mainLoop.isRunning()) {
            mainLoop.execute(this::beginShutdown);
            awaitClients(SHUTDOWN_GRACE_MILLIS);
        }
        closeQuietly(serverChannel);
        mainLoop.shutdown();
        for (EventLoop loop : ioLoops) {
            if (loop != mainLoop) {
                loop.shutdown();
            }
        }
        try {
            mainLoop.awaitTermination(JOIN_MILLIS);
            for (EventLoop loop : ioLoops) {
                if (loop != mainLoop) {
                    loop.awaitTermination(JOIN_MILLIS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        clients.clear();
        LOG.log(System.Logger.Level.INFO, "RedisJ server stopped");
    }

    @Override
    public void onRequests(ClientConnection client, List<RespValue> requests, String protocolError) {
        mainLoop.execute(() -> execute(client, requests, protocolError));
    }

    @Override
    public void onClosed(ClientConnection client) {
        clients.remove(client.id());
    }

    private EventLoop[] buildIoLoops() throws IOException {
        int count = options.ioThreads();
        if (count == 0) {
            return new EventLoop[] {mainLoop};
        }
        EventLoop[] loops = new EventLoop[count];
        for (int i = 0; i < count; i++) {
            loops[i] = new EventLoop("redisj-io-" + i, null);
        }
        return loops;
    }

    /** Runs on the main loop: accepts every pending connection. */
    private void acceptPending() {
        while (true) {
            SocketChannel channel;
            try {
                channel = serverChannel.accept();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "accept failed", e);
                return;
            }
            if (channel == null) {
                return;
            }
            if (clients.size() >= options.maxClients()) {
                rejectFull(channel);
                continue;
            }
            try {
                channel.configureBlocking(false);
                channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            } catch (IOException e) {
                closeQuietly(channel);
                continue;
            }
            long id = nextClientId.getAndIncrement();
            EventLoop owner = ioLoops[Math.floorMod(nextLoop.getAndIncrement(), ioLoops.length)];
            ClientConnection connection = new ClientConnection(id, channel, owner, options, this,
                    options.requirePass() == null);
            clients.put(id, connection);
            owner.execute(connection::register);
        }
    }

    /** Runs on the main loop. Executes one batch of requests for one client, in order. */
    private void execute(ClientConnection client, List<RespValue> requests, String protocolError) {
        for (RespValue request : requests) {
            if (client.isClosingSoon()) {
                break;
            }
            RespValue reply;
            try {
                reply = handler.handle(client, request);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "command handler failed", e);
                reply = new RespValue.Error("ERR internal error");
            }
            if (reply != null) {
                client.reply(reply);
            }
        }
        if (protocolError != null) {
            client.reply(new RespValue.Error("ERR " + protocolError));
            client.closeAfterFlush();
        }
    }

    /** Runs on the main loop every ~100 ms: closes clients idle past the configured timeout. */
    private void tick() {
        int timeout = options.idleTimeoutSeconds();
        if (timeout <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long limit = timeout * 1000L;
        for (ClientConnection client : clients.values()) {
            if (now - client.lastActivityMillis() > limit) {
                client.requestClose();
            }
        }
    }

    /** Runs on the main loop during shutdown: stop accepting and close clients after their replies. */
    private void beginShutdown() {
        closeQuietly(serverChannel);
        for (ClientConnection client : clients.values()) {
            client.closeAfterFlush();
        }
    }

    private void awaitClients(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        try {
            while (!clients.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void rejectFull(SocketChannel channel) {
        try {
            channel.write(ByteBuffer.wrap(MAX_CLIENTS_REPLY));
        } catch (IOException ignored) {
            // The client is being turned away anyway.
        }
        closeQuietly(channel);
    }

    private static void closeQuietly(java.nio.channels.Channel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Best effort.
        }
    }
}
