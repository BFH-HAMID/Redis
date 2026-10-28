package dev.hamid.redisj.config;

import dev.hamid.redisj.store.EvictionPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Immutable server configuration.
 *
 * <p>Instances are created through {@link #builder()}, {@link #defaults()} or
 * {@link #load(Path)}. Every setter on {@link Builder} validates its input so an
 * invalid instance can never exist.
 */
public final class ServerConfig {

    /** Default TCP port (the Redis default). */
    public static final int DEFAULT_PORT = 6379;
    /** Default bind address; loopback only, which is the safe default. */
    public static final String DEFAULT_BIND = "127.0.0.1";
    /** Default number of logical databases. */
    public static final int DEFAULT_DATABASES = 16;
    /** Upper bound on the number of logical databases. */
    public static final int MAX_DATABASES = 1024;
    /** Default number of I/O threads; {@code 0} means the event loop does all socket work. */
    public static final int DEFAULT_IO_THREADS = 0;
    /** Upper bound on I/O threads. */
    public static final int MAX_IO_THREADS = 64;
    /** Default maximum number of simultaneous clients. */
    public static final int DEFAULT_MAX_CLIENTS = 10_000;

    private final int port;
    private final String bind;
    private final String requirePass;
    private final boolean appendOnly;
    private final long maxMemory;
    private final String maxMemoryPolicy;
    private final int databases;
    private final int timeoutSeconds;
    private final int ioThreads;
    private final int maxClients;
    private final boolean clusterEnabled;
    private final String clusterMyself;
    private final java.util.List<ClusterNodeConfig> clusterNodes;

    private ServerConfig(Builder b) {
        this.port = b.port;
        this.bind = b.bind;
        this.requirePass = b.requirePass;
        this.appendOnly = b.appendOnly;
        this.maxMemory = b.maxMemory;
        this.maxMemoryPolicy = b.maxMemoryPolicy;
        this.databases = b.databases;
        this.timeoutSeconds = b.timeoutSeconds;
        this.ioThreads = b.ioThreads;
        this.maxClients = b.maxClients;
        this.clusterEnabled = b.clusterEnabled;
        this.clusterMyself = b.clusterMyself;
        this.clusterNodes = java.util.List.copyOf(b.clusterNodes);
    }

    /**
     * Returns a configuration with all default values.
     *
     * @return the default configuration
     */
    public static ServerConfig defaults() {
        return builder().build();
    }

    /**
     * Returns a new builder initialised with default values.
     *
     * @return a fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Loads and parses a {@code redisj.conf} file.
     *
     * @param file path of the configuration file
     * @return the parsed configuration
     * @throws IOException if the file cannot be read
     * @throws ConfigException if the file contains an invalid directive
     */
    public static ServerConfig load(Path file) throws IOException {
        return ConfigParser.parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * Returns a builder pre-populated with the values of this configuration.
     *
     * @return a builder that can derive a modified configuration
     */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.port = port;
        b.bind = bind;
        b.requirePass = requirePass;
        b.appendOnly = appendOnly;
        b.maxMemory = maxMemory;
        b.maxMemoryPolicy = maxMemoryPolicy;
        b.databases = databases;
        b.timeoutSeconds = timeoutSeconds;
        b.ioThreads = ioThreads;
        b.maxClients = maxClients;
        b.clusterEnabled = clusterEnabled;
        b.clusterMyself = clusterMyself;
        b.clusterNodes.addAll(clusterNodes);
        return b;
    }

    /**
     * @return the TCP port to listen on
     */
    public int port() {
        return port;
    }

    /**
     * @return the address to bind the listening socket to
     */
    public String bind() {
        return bind;
    }

    /**
     * @return the password required by {@code AUTH}, if any
     */
    public Optional<String> requirePass() {
        return Optional.ofNullable(requirePass);
    }

    /**
     * @return {@code true} if the append-only file is enabled
     */
    public boolean appendOnly() {
        return appendOnly;
    }

    /**
     * @return the memory limit in bytes; {@code 0} means unlimited
     */
    public long maxMemory() {
        return maxMemory;
    }

    /**
     * @return the eviction policy name, {@code noeviction} or {@code allkeys-random}
     */
    public String maxMemoryPolicy() {
        return maxMemoryPolicy;
    }

    /**
     * @return the number of logical databases
     */
    public int databases() {
        return databases;
    }

    /**
     * @return the idle client timeout in seconds; {@code 0} disables it
     */
    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * @return the number of I/O threads; {@code 0} means the event loop handles sockets itself
     */
    public int ioThreads() {
        return ioThreads;
    }

    /**
     * @return the maximum number of simultaneous client connections
     */
    public int maxClients() {
        return maxClients;
    }

    /**
     * @return {@code true} if cluster mode is enabled
     */
    public boolean clusterEnabled() {
        return clusterEnabled;
    }

    /**
     * @return the id of this node in the cluster, or {@code null}
     */
    public String clusterMyself() {
        return clusterMyself;
    }

    /**
     * @return the configured cluster nodes, in directive order
     */
    public java.util.List<ClusterNodeConfig> clusterNodes() {
        return clusterNodes;
    }

    /**
     * Returns a description that never reveals the password.
     *
     * @return a diagnostic string
     */
    @Override
    public String toString() {
        return "ServerConfig{bind=" + bind + ", port=" + port + ", databases=" + databases
                + ", maxmemory=" + maxMemory + ", appendonly=" + appendOnly
                + ", timeout=" + timeoutSeconds + ", io-threads=" + ioThreads + ", maxclients=" + maxClients + ", requirepass="
                + (requirePass == null ? "<unset>" : "<redacted>") + "}";
    }

    /**
     * Mutable builder for {@link ServerConfig}. Setters throw {@link IllegalArgumentException}
     * when a value is out of range.
     */
    public static final class Builder {

        private int port = DEFAULT_PORT;
        private String bind = DEFAULT_BIND;
        private String requirePass;
        private boolean appendOnly;
        private long maxMemory;
        private String maxMemoryPolicy = "noeviction";
        private int databases = DEFAULT_DATABASES;
        private int timeoutSeconds;
        private int ioThreads = DEFAULT_IO_THREADS;
        private int maxClients = DEFAULT_MAX_CLIENTS;
        private boolean clusterEnabled;
        private String clusterMyself;
        private final java.util.List<ClusterNodeConfig> clusterNodes = new java.util.ArrayList<>();

        private Builder() {
        }

        /**
         * @param port TCP port, between 1 and 65535
         * @return this builder
         */
        public Builder port(int port) {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port must be between 1 and 65535: " + port);
            }
            this.port = port;
            return this;
        }

        /**
         * @param bind non-blank bind address
         * @return this builder
         */
        public Builder bind(String bind) {
            if (bind == null || bind.isBlank()) {
                throw new IllegalArgumentException("bind address must not be blank");
            }
            this.bind = bind.trim();
            return this;
        }

        /**
         * @param password the password, or {@code null}/empty to disable authentication
         * @return this builder
         */
        public Builder requirePass(String password) {
            this.requirePass = (password == null || password.isEmpty()) ? null : password;
            return this;
        }

        /**
         * @param enabled whether the append-only file is enabled
         * @return this builder
         */
        public Builder appendOnly(boolean enabled) {
            this.appendOnly = enabled;
            return this;
        }

        /**
         * @param bytes memory limit in bytes, {@code 0} for unlimited
         * @return this builder
         */
        public Builder maxMemoryPolicy(String policy) {
            this.maxMemoryPolicy = EvictionPolicy.parse(policy).directive();
            return this;
        }

        /**
         * @param bytes memory limit in bytes, {@code 0} for unlimited
         * @return this builder
         */
        public Builder maxMemory(long bytes) {
            if (bytes < 0) {
                throw new IllegalArgumentException("maxmemory must not be negative: " + bytes);
            }
            this.maxMemory = bytes;
            return this;
        }

        /**
         * @param count number of logical databases, between 1 and {@link #MAX_DATABASES}
         * @return this builder
         */
        public Builder databases(int count) {
            if (count < 1 || count > MAX_DATABASES) {
                throw new IllegalArgumentException(
                        "databases must be between 1 and " + MAX_DATABASES + ": " + count);
            }
            this.databases = count;
            return this;
        }

        /**
         * @param seconds idle timeout in seconds, {@code 0} to disable
         * @return this builder
         */
        public Builder timeoutSeconds(int seconds) {
            if (seconds < 0) {
                throw new IllegalArgumentException("timeout must not be negative: " + seconds);
            }
            this.timeoutSeconds = seconds;
            return this;
        }

        /**
         * @param count number of I/O threads, between 0 and {@link #MAX_IO_THREADS}
         * @return this builder
         */
        public Builder ioThreads(int count) {
            if (count < 0 || count > MAX_IO_THREADS) {
                throw new IllegalArgumentException("io-threads must be between 0 and " + MAX_IO_THREADS + ": " + count);
            }
            this.ioThreads = count;
            return this;
        }

        /**
         * @param limit maximum number of clients, at least 1
         * @return this builder
         */
        /**
         * @param enabled {@code true} to enable cluster mode
         * @return this builder
         */
        public Builder clusterEnabled(boolean enabled) {
            this.clusterEnabled = enabled;
            return this;
        }

        /**
         * @param id this node's id, which must match one {@code cluster-node}
         * @return this builder
         */
        public Builder clusterMyself(String id) {
            this.clusterMyself = id;
            return this;
        }

        /**
         * @param node a cluster node and its slot range
         * @return this builder
         */
        public Builder clusterNode(ClusterNodeConfig node) {
            this.clusterNodes.add(node);
            return this;
        }

        /**
         * @param limit maximum simultaneous clients
         * @return this builder
         */
        public Builder maxClients(int limit) {
            if (limit < 1) {
                throw new IllegalArgumentException("maxclients must be at least 1: " + limit);
            }
            this.maxClients = limit;
            return this;
        }

        /**
         * @return an immutable configuration built from the current values
         */
        public ServerConfig build() {
            return new ServerConfig(this);
        }
    }
}
