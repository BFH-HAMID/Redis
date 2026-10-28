package dev.hamid.redisj.server;

import dev.hamid.redisj.protocol.ProtocolVersion;
import dev.hamid.redisj.protocol.RespParser;
import dev.hamid.redisj.protocol.RespProtocolException;
import dev.hamid.redisj.protocol.RespValue;
import dev.hamid.redisj.protocol.RespWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One client connection.
 *
 * <p>The connection is shared by two threads, and each field is confined to one of them:
 * <ul>
 *   <li><b>Owner I/O loop</b> (an {@link EventLoop}): reads, parses, writes, registers and closes
 *       the channel. It also drives backpressure by changing interest ops.</li>
 *   <li><b>Execution loop</b> (the server's main loop): session state such as name, selected
 *       database, authentication and protocol version. Replies are encoded here and queued for the
 *       owner to write.</li>
 * </ul>
 * Replies cross threads through a concurrent queue, and an {@link AtomicLong} counts pending bytes.
 * Only the owner calls the channel.
 *
 * <p>Backpressure: when more than {@link ServerOptions#outputHighWaterBytes()} bytes are pending the
 * owner stops reading from the client until the backlog drains to half that size. When pending
 * output exceeds {@link ServerOptions#outputHardLimitBytes()} the client is closed.
 */
public final class ClientConnection implements SelectHandler {

    private static final RespWriter RESP2_WRITER = new RespWriter(ProtocolVersion.RESP2);
    private static final RespWriter RESP3_WRITER = new RespWriter(ProtocolVersion.RESP3);

    private final long id;
    private final SocketChannel channel;
    private final String remoteAddress;
    private final EventLoop owner;
    private final ServerOptions options;
    private final ClientEvents events;

    // Written by the execution loop, read by the owner loop.
    private final ConcurrentLinkedQueue<ByteBuffer> outbox = new ConcurrentLinkedQueue<>();
    private final AtomicLong pendingBytes = new AtomicLong();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private volatile boolean closeAfterFlush;
    private volatile boolean closing;
    private volatile long lastActivityMillis;

    // Owner-loop confined.
    private final RespParser parser = new RespParser();
    private ByteBuffer readBuffer;
    private SelectionKey key;
    private ByteBuffer writing;
    private boolean readPaused;
    private boolean protocolFailed;
    private boolean closed;

    // Execution-loop confined session state.
    private String name;
    private int db;
    private boolean authenticated;
    private ProtocolVersion protocol = ProtocolVersion.RESP2;

    ClientConnection(long id, SocketChannel channel, EventLoop owner, ServerOptions options,
                     ClientEvents events, boolean authenticated) {
        this.id = id;
        this.channel = channel;
        this.owner = owner;
        this.options = options;
        this.events = events;
        this.authenticated = authenticated;
        this.readBuffer = ByteBuffer.allocate(options.readBufferBytes());
        this.remoteAddress = describeRemote(channel);
        this.lastActivityMillis = System.currentTimeMillis();
    }

    // ---------------------------------------------------------------- execution-loop API

    /**
     * @return the unique connection id
     */
    public long id() {
        return id;
    }

    /**
     * @return the peer address as {@code host:port}
     */
    public String remoteAddress() {
        return remoteAddress;
    }

    /**
     * @return the client name set with {@code CLIENT SETNAME}, if any
     */
    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    /**
     * @param name the new name, or {@code null} to clear it
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return the index of the selected logical database
     */
    public int db() {
        return db;
    }

    /**
     * @param index the database index to select
     */
    public void selectDb(int index) {
        this.db = index;
    }

    /**
     * @return {@code true} once the client has authenticated (or no password is required)
     */
    public boolean isAuthenticated() {
        return authenticated;
    }

    /**
     * Marks the client as authenticated.
     */
    public void markAuthenticated() {
        this.authenticated = true;
    }

    /**
     * @return the protocol version used to encode replies
     */
    public ProtocolVersion protocol() {
        return protocol;
    }

    /**
     * @param version the protocol version for subsequent replies
     */
    public void setProtocol(ProtocolVersion version) {
        this.protocol = version;
    }

    /**
     * @return {@code true} if the connection is closing or will close after its pending replies
     */
    public boolean isClosingSoon() {
        return closing || closeAfterFlush;
    }

    /**
     * Encodes a reply and queues it for writing. Output beyond the hard limit closes the client.
     *
     * @param value the reply
     */
    public void reply(RespValue value) {
        if (owner == null) {
            return;
        }
        RespWriter writer = protocol == ProtocolVersion.RESP3 ? RESP3_WRITER : RESP2_WRITER;
        int size = writer.encodedLength(value);
        ByteBuffer buffer = ByteBuffer.allocate(size);
        writer.write(value, buffer);
        buffer.flip();
        long total = pendingBytes.addAndGet(size);
        outbox.add(buffer);
        if (total > options.outputHardLimitBytes()) {
            requestClose();
            return;
        }
        scheduleFlush();
    }

    /**
     * Closes the connection once all queued replies have been written. Reading stops immediately.
     */
    public void closeAfterFlush() {
        closeAfterFlush = true;
        scheduleFlush();
    }

    /**
     * Closes the connection now, discarding unsent replies. Safe to call from any thread.
     */
    public void requestClose() {
        if (owner != null && !closing) {
            closing = true;
            owner.execute(this::closeNow);
        }
    }

    /**
     * @return the last time (ms since the epoch) bytes were read from the client
     */
    public long lastActivityMillis() {
        return lastActivityMillis;
    }

    /**
     * Creates a connection with no socket, used to replay commands at startup. Replies are
     * discarded, and close requests are ignored.
     *
     * @param id the id to give the connection
     * @return a detached, already authenticated connection
     */
    public static ClientConnection detached(long id) {
        return new ClientConnection(id, null, null, ServerOptions.ephemeral(), null, true);
    }

    // ---------------------------------------------------------------- owner-loop internals

    /**
     * Registers the channel with the owner loop. Must run on the owner loop thread.
     */
    void register() {
        if (closed) {
            return;
        }
        try {
            key = channel.register(owner.selector(), SelectionKey.OP_READ, this);
        } catch (ClosedChannelException e) {
            closeNow();
        }
    }

    @Override
    public void onReady(SelectionKey k) {
        if (closed || !k.isValid()) {
            return;
        }
        if (k.isReadable()) {
            onReadable();
        }
        if (!closed && k.isValid() && k.isWritable()) {
            flushOutbox();
        }
    }

    private void onReadable() {
        if (!readBuffer.hasRemaining()) {
            if (!growReadBuffer()) {
                closeNow();
                return;
            }
        }
        int n;
        try {
            n = channel.read(readBuffer);
        } catch (IOException e) {
            closeNow();
            return;
        }
        if (n < 0) {
            closeNow();
            return;
        }
        if (n == 0) {
            return;
        }
        lastActivityMillis = System.currentTimeMillis();
        parseAndDispatch();
    }

    private void parseAndDispatch() {
        readBuffer.flip();
        List<RespValue> requests = new ArrayList<>();
        String error = null;
        try {
            RespValue value;
            while ((value = parser.parse(readBuffer)) != null) {
                requests.add(value);
            }
        } catch (RespProtocolException e) {
            error = e.getMessage();
        }
        readBuffer.compact();
        if (error != null) {
            protocolFailed = true;
            updateInterest();
        }
        if (error != null || !requests.isEmpty()) {
            events.onRequests(this, requests, error);
        }
    }

    /**
     * Doubles the read buffer so a large frame fits. Returns {@code false} at the protocol maximum.
     */
    private boolean growReadBuffer() {
        int max = RespParser.MAX_BULK_LENGTH + 64 * 1024;
        int capacity = readBuffer.capacity();
        if (capacity >= max) {
            return false;
        }
        int newCapacity = Math.min(max, capacity * 2);
        ByteBuffer bigger = ByteBuffer.allocate(newCapacity);
        readBuffer.flip();
        bigger.put(readBuffer);
        readBuffer = bigger;
        return true;
    }

    /**
     * Schedules a flush on the owner loop. Coalesces repeated calls into one task.
     */
    private void scheduleFlush() {
        if (owner == null) {
            return;
        }
        if (flushScheduled.compareAndSet(false, true)) {
            owner.execute(this::flushFromOwner);
        }
    }

    private void flushFromOwner() {
        flushScheduled.set(false);
        if (!closed) {
            flushOutbox();
        }
    }

    private void flushOutbox() {
        try {
            while (true) {
                if (writing == null) {
                    writing = outbox.poll();
                    if (writing == null) {
                        break;
                    }
                }
                int written = channel.write(writing);
                if (written > 0) {
                    pendingBytes.addAndGet(-written);
                }
                if (writing.hasRemaining()) {
                    break;
                }
                writing = null;
            }
        } catch (IOException e) {
            closeNow();
            return;
        }
        if (writing == null && outbox.isEmpty() && closeAfterFlush) {
            closeNow();
            return;
        }
        updateInterest();
    }

    /**
     * Recomputes the interest set from the pending output, applying backpressure hysteresis.
     */
    private void updateInterest() {
        if (closed || key == null || !key.isValid()) {
            return;
        }
        long pending = pendingBytes.get();
        long high = options.outputHighWaterBytes();
        if (!readPaused && pending > high) {
            readPaused = true;
        } else if (readPaused && pending <= high / 2) {
            readPaused = false;
        }
        boolean hasOutput = writing != null || !outbox.isEmpty();
        int ops = 0;
        if (!readPaused && !protocolFailed && !closing && !closeAfterFlush) {
            ops |= SelectionKey.OP_READ;
        }
        if (hasOutput) {
            ops |= SelectionKey.OP_WRITE;
        }
        key.interestOps(ops);
    }

    private void closeNow() {
        if (closed) {
            return;
        }
        closed = true;
        closing = true;
        if (key != null) {
            key.cancel();
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Closing a socket can fail if the peer already vanished; the connection is gone anyway.
        }
        writing = null;
        outbox.clear();
        pendingBytes.set(0);
        events.onClosed(this);
    }

    private static String describeRemote(SocketChannel channel) {
        if (channel == null) {
            return "detached";
        }
        try {
            return String.valueOf(channel.getRemoteAddress());
        } catch (IOException e) {
            return "unknown";
        }
    }
}
