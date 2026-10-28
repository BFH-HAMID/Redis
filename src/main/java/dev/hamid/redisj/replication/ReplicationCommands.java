package dev.hamid.redisj.replication;

import dev.hamid.redisj.command.Args;
import dev.hamid.redisj.command.Command;
import dev.hamid.redisj.command.CommandContext;
import dev.hamid.redisj.command.CommandException;
import dev.hamid.redisj.command.CommandRegistry;
import dev.hamid.redisj.command.Replies;
import dev.hamid.redisj.command.ServerState;
import dev.hamid.redisj.persistence.Replayer;
import dev.hamid.redisj.persistence.SnapshotWriter;
import dev.hamid.redisj.protocol.RespValue;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Minimal replication: {@code SYNC} on a master, and {@code REPLICAOF} on a replica.
 *
 * <p>This is a full sync only. A replica copies the master's dataset once, when {@code REPLICAOF}
 * runs, and then does not receive later writes. The dataset is sent as a RedisJ snapshot, the same
 * format as {@code SAVE}. Replicas are read-only until {@code REPLICAOF NO ONE}.
 *
 * <p>The transfer runs on the execution loop and blocks it, which keeps the state consistent but
 * means a slow or dead master stalls the replica until the timeouts expire.
 */
public final class ReplicationCommands {

    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;
    private static final Set<Command.Flag> ADMIN = Set.of(Command.Flag.ADMIN);

    private ReplicationCommands() {
    }

    /**
     * Registers {@code SYNC} and {@code REPLICAOF}.
     *
     * @param registry the registry to add to; {@code REPLICAOF} replays the snapshot through it
     */
    public static void register(CommandRegistry registry) {
        registry.register(Command.of("SYNC", 1, ADMIN, 0, 0, 0, ReplicationCommands::sync));
        registry.register(Command.of("REPLICAOF", 3, ADMIN, 0, 0, 0,
                ctx -> replicaOf(ctx, registry)));
    }

    static RespValue sync(CommandContext ctx) {
        try {
            return new RespValue.BulkString(SnapshotWriter.toBytes(ctx.server()));
        } catch (IOException e) {
            throw new CommandException("ERR snapshot failed: " + e.getMessage());
        }
    }

    static RespValue replicaOf(CommandContext ctx, CommandRegistry registry) {
        Args a = ctx.args();
        ServerState state = ctx.server();
        if (a.upper(1).equals("NO") && a.upper(2).equals("ONE")) {
            state.setReplicaOf(null);
            return Replies.ok();
        }
        String host = a.text(1);
        int port = parsePort(a.text(2));
        if (state.appendOnly()) {
            throw new CommandException("ERR REPLICAOF is not supported while appendonly is enabled");
        }
        byte[] snapshot;
        try {
            snapshot = fetchSnapshot(host, port);
        } catch (IOException e) {
            throw new CommandException("ERR Sync with master failed: " + e.getMessage());
        }
        for (int index = 0; index < state.databases().count(); index++) {
            state.databases().get(index).clear();
        }
        state.setReplaying(true);
        try {
            Replayer.applyBytes(snapshot, registry);
        } catch (IOException e) {
            throw new CommandException("ERR Loading master snapshot failed: " + e.getMessage());
        } finally {
            state.setReplaying(false);
        }
        state.setReplicaOf(host + ":" + port);
        return Replies.ok();
    }

    /** Asks the master for a snapshot using SYNC and returns the bulk payload. */
    static byte[] fetchSnapshot(String host, int port) throws IOException {
        InetSocketAddress address = new InetSocketAddress(host, port);
        if (address.isUnresolved()) {
            throw new IOException("cannot resolve " + host);
        }
        try (Socket socket = new Socket()) {
            socket.connect(address, CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write("*1\r\n$4\r\nSYNC\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = new BufferedInputStream(socket.getInputStream());
            String header = readLine(in);
            if (header.startsWith("-")) {
                throw new IOException(header.substring(1));
            }
            if (!header.startsWith("$")) {
                throw new IOException("unexpected reply: " + header);
            }
            long length;
            try {
                length = Long.parseLong(header.substring(1));
            } catch (NumberFormatException e) {
                throw new IOException("bad payload length: " + header, e);
            }
            if (length < 0 || length > Integer.MAX_VALUE) {
                throw new IOException("bad payload length: " + length);
            }
            byte[] payload = in.readNBytes((int) length);
            if (payload.length != length) {
                throw new IOException("connection closed during transfer");
            }
            return payload;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                line.write(c);
            }
        }
        if (c == -1 && line.size() == 0) {
            throw new IOException("master closed the connection");
        }
        return line.toString(StandardCharsets.UTF_8);
    }

    private static int parsePort(String text) {
        try {
            int port = Integer.parseInt(text);
            if (port < 1 || port > 65_535) {
                throw new CommandException("ERR Invalid master port");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new CommandException("ERR Invalid master port");
        }
    }
}
