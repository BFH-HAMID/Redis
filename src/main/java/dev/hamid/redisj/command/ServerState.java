package dev.hamid.redisj.command;

import dev.hamid.redisj.pubsub.PubSub;
import dev.hamid.redisj.server.ClientConnection;
import dev.hamid.redisj.store.EvictionPolicy;
import dev.hamid.redisj.store.Databases;
import dev.hamid.redisj.store.ExpiryManager;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Server-wide state that commands read and update: the databases, the expiry manager, the
 * configured password and a view of the connected clients.
 *
 * <p>Used only from the execution loop.
 */
public final class ServerState {

    /** Server version reported by {@code HELLO} and {@code INFO}. */
    public static final String VERSION = "0.1.0-SNAPSHOT";

    private final Databases databases;
    private final ExpiryManager expiry;
    private final WatchTable watches = new WatchTable();
    private final PubSub pubsub = new PubSub();
    private long maxMemory;
    private EvictionPolicy evictionPolicy = EvictionPolicy.NOEVICTION;
    private boolean replaying;
    private String replicaOf;
    private boolean appendOnly;
    private WriteLog writeLog = WriteLog.NONE;
    private final Map<ClientConnection, ClientTransaction> transactions = new HashMap<>();
    private final String requirePass;
    private final long startedAtMillis;
    private volatile Supplier<Collection<ClientConnection>> clients = () -> List.of();

    /**
     * @param databaseCount number of logical databases
     * @param requirePass   the password clients must authenticate with, or {@code null}
     * @param clock         time source in epoch milliseconds
     */
    public ServerState(int databaseCount, String requirePass, LongSupplier clock) {
        this.databases = new Databases(databaseCount, Objects.requireNonNull(clock, "clock"));
        this.expiry = new ExpiryManager(databases, new Random());
        this.requirePass = (requirePass == null || requirePass.isEmpty()) ? null : requirePass;
        this.startedAtMillis = clock.getAsLong();
    }

    /**
     * Connects the client directory. Called once after the server is created.
     *
     * @param supplier returns the currently connected clients
     */
    public void attachClients(Supplier<Collection<ClientConnection>> supplier) {
        this.clients = Objects.requireNonNull(supplier, "supplier");
    }

    /**
     * @return the currently connected clients
     */
    public Collection<ClientConnection> clients() {
        return clients.get();
    }

    /**
     * @param client the client
     * @return the client's transaction state, created on first use
     */
    public ClientTransaction transaction(ClientConnection client) {
        return transactions.computeIfAbsent(client, c -> new ClientTransaction());
    }

    /**
     * @param client the client
     * @return the client's transaction state, or {@code null} if it never used transactions
     */
    public ClientTransaction findTransaction(ClientConnection client) {
        return transactions.get(client);
    }

    /**
     * @return the sink that records successful writes (persistence), or a no-op sink
     */
    public WriteLog writeLog() {
        return writeLog;
    }

    /**
     * Replaces the write log. Call after any replay, so that replayed writes are not logged again.
     *
     * @param log the new sink
     */
    public void attachWriteLog(WriteLog log) {
        this.writeLog = Objects.requireNonNull(log, "log");
    }

    /**
     * @return the table of watched keys
     */
    public WatchTable watches() {
        return watches;
    }

    /**
     * Drops a disconnected client's transaction state and watches.
     *
     * @param client the client that closed
     */
    public void releaseClient(ClientConnection client) {
        ClientTransaction tx = transactions.remove(client);
        if (tx != null) {
            watches.unwatchAll(tx);
        }
        pubsub.releaseClient(client);
    }

    /**
     * Sets the memory limit and eviction policy. Called once at startup, before the server accepts
     * clients.
     *
     * @param maxMemoryBytes the limit in bytes, {@code 0} for unlimited
     * @param policy         what to do when the limit is exceeded
     */
    public void configureMemory(long maxMemoryBytes, EvictionPolicy policy) {
        this.maxMemory = maxMemoryBytes;
        this.evictionPolicy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * @return the memory limit in bytes, {@code 0} for unlimited
     */
    public long maxMemory() {
        return maxMemory;
    }

    /**
     * @return the eviction policy
     */
    public EvictionPolicy evictionPolicy() {
        return evictionPolicy;
    }

    /**
     * Marks the server as replaying its persistence files. Eviction is skipped while replaying,
     * because the files already record every eviction.
     *
     * @param replaying {@code true} while replaying
     */
    public void setReplaying(boolean replaying) {
        this.replaying = replaying;
    }

    /**
     * @return {@code true} while replaying persistence files
     */
    public boolean replaying() {
        return replaying;
    }

    /**
     * Records whether the append-only file is enabled. Replication needs to know this, because a
     * snapshot load would otherwise leave stale records in the file.
     *
     * @param enabled {@code true} if {@code appendonly} is on
     */
    public void setAppendOnly(boolean enabled) {
        this.appendOnly = enabled;
    }

    /**
     * @return {@code true} if the append-only file is enabled
     */
    public boolean appendOnly() {
        return appendOnly;
    }

    /**
     * Sets or clears the master this server replicates. Used by the replication commands.
     *
     * @param master {@code host:port}, or {@code null} to stop replicating
     */
    public void setReplicaOf(String master) {
        this.replicaOf = master;
    }

    /**
     * @return {@code host:port} of the master, or empty if this server is not a replica
     */
    public Optional<String> replicaOf() {
        return Optional.ofNullable(replicaOf);
    }

    /**
     * @return the publish/subscribe registry
     */
    public PubSub pubsub() {
        return pubsub;
    }

    /**
     * @return the databases
     */
    public Databases databases() {
        return databases;
    }

    /**
     * @return the active expiry manager
     */
    public ExpiryManager expiry() {
        return expiry;
    }

    /**
     * @return {@code true} if clients must authenticate before running commands
     */
    public boolean requiresPassword() {
        return requirePass != null;
    }

    /**
     * @return the configured password, if any
     */
    public Optional<String> requirePass() {
        return Optional.ofNullable(requirePass);
    }

    /**
     * @return the start time in epoch milliseconds
     */
    public long startedAtMillis() {
        return startedAtMillis;
    }
}
