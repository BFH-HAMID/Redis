package dev.hamid.redisj.server;

import dev.hamid.redisj.config.ServerConfig;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * Immutable networking options for {@link RedisServer}.
 *
 * @param address                the socket address to listen on; port {@code 0} picks a free port
 * @param ioThreads              number of I/O threads, {@code 0} for none (the loop does all I/O)
 * @param maxClients             maximum simultaneous clients
 * @param idleTimeoutSeconds     close clients idle for longer than this; {@code 0} disables it
 * @param requirePass            password that clients must AUTH with, or {@code null}
 * @param outputHighWaterBytes   pending output above this pauses reading from the client
 * @param outputHardLimitBytes   pending output above this closes the client
 * @param readBufferBytes        initial per-client read buffer size
 */
public record ServerOptions(InetSocketAddress address, int ioThreads, int maxClients,
                            int idleTimeoutSeconds, String requirePass, int outputHighWaterBytes,
                            long outputHardLimitBytes, int readBufferBytes) {

    /** Largest accepted number of I/O threads. */
    public static final int MAX_IO_THREADS = ServerConfig.MAX_IO_THREADS;

    /** Validates the options. */
    public ServerOptions {
        Objects.requireNonNull(address, "address");
        if (ioThreads < 0 || ioThreads > MAX_IO_THREADS) {
            throw new IllegalArgumentException("ioThreads must be between 0 and " + MAX_IO_THREADS);
        }
        if (maxClients < 1) {
            throw new IllegalArgumentException("maxClients must be at least 1");
        }
        if (idleTimeoutSeconds < 0) {
            throw new IllegalArgumentException("idleTimeoutSeconds must not be negative");
        }
        if (outputHighWaterBytes < 1) {
            throw new IllegalArgumentException("outputHighWaterBytes must be positive");
        }
        if (outputHardLimitBytes < outputHighWaterBytes) {
            throw new IllegalArgumentException("outputHardLimitBytes must be at least the high-water mark");
        }
        if (readBufferBytes < 1024) {
            throw new IllegalArgumentException("readBufferBytes must be at least 1024");
        }
    }

    /**
     * Returns options that listen on a free loopback port with default limits. Intended for tests.
     *
     * @return ephemeral-port options
     */
    public static ServerOptions ephemeral() {
        return new ServerOptions(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                0, 10_000, 0, null, 256 * 1024, 64L * 1024 * 1024, 16 * 1024);
    }

    /**
     * Derives networking options from a parsed configuration.
     *
     * @param config the server configuration
     * @return the options
     * @throws UnknownHostException if the bind address cannot be resolved
     */
    public static ServerOptions from(ServerConfig config) throws UnknownHostException {
        return new ServerOptions(new InetSocketAddress(InetAddress.getByName(config.bind()), config.port()),
                config.ioThreads(), config.maxClients(), config.timeoutSeconds(),
                config.requirePass().orElse(null), 256 * 1024, 64L * 1024 * 1024, 16 * 1024);
    }

    /**
     * @param count the number of I/O threads
     * @return a copy with the given I/O thread count
     */
    public ServerOptions withIoThreads(int count) {
        return new ServerOptions(address, count, maxClients, idleTimeoutSeconds, requirePass,
                outputHighWaterBytes, outputHardLimitBytes, readBufferBytes);
    }

    /**
     * @param limit the maximum number of clients
     * @return a copy with the given client limit
     */
    public ServerOptions withMaxClients(int limit) {
        return new ServerOptions(address, ioThreads, limit, idleTimeoutSeconds, requirePass,
                outputHighWaterBytes, outputHardLimitBytes, readBufferBytes);
    }

    /**
     * @param seconds the idle timeout in seconds, {@code 0} to disable
     * @return a copy with the given idle timeout
     */
    public ServerOptions withIdleTimeoutSeconds(int seconds) {
        return new ServerOptions(address, ioThreads, maxClients, seconds, requirePass,
                outputHighWaterBytes, outputHardLimitBytes, readBufferBytes);
    }
}
