package dev.hamid.redisj.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hamid.redisj.protocol.RespValue;
import dev.hamid.redisj.server.ClientConnection;
import dev.hamid.redisj.store.EvictionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvictionTest {

    private static final String VALUE = "x".repeat(300);

    private static RespValue run(CommandRegistry registry, ClientConnection client, String... parts) {
        List<RespValue> elements = new ArrayList<>(parts.length);
        for (String part : parts) {
            elements.add(new RespValue.BulkString(part.getBytes(StandardCharsets.UTF_8)));
        }
        return registry.handle(client, new RespValue.Array(elements));
    }

    @Test
    void allkeysRandomKeepsTheDatasetWithinTheLimit() {
        ServerState state = new ServerState(16, null, System::currentTimeMillis);
        state.configureMemory(4000, EvictionPolicy.ALLKEYS_RANDOM);
        CommandRegistry registry = CommandRegistry.withDefaults(state);
        ClientConnection client = ClientConnection.detached(1);
        for (int i = 0; i < 50; i++) {
            RespValue reply = run(registry, client, "SET", "k" + i, VALUE);
            assertTrue(reply instanceof RespValue.SimpleString, "SET k" + i + " was " + reply);
        }
        long keys = ((RespValue.Integer) run(registry, client, "DBSIZE")).value();
        assertTrue(keys > 0 && keys <= 10, "keys left: " + keys);
    }

    @Test
    void noevictionRefusesWritesOverTheLimit() {
        ServerState state = new ServerState(16, null, System::currentTimeMillis);
        state.configureMemory(1000, EvictionPolicy.NOEVICTION);
        CommandRegistry registry = CommandRegistry.withDefaults(state);
        ClientConnection client = ClientConnection.detached(1);
        RespValue refused = null;
        for (int i = 0; i < 20 && refused == null; i++) {
            RespValue reply = run(registry, client, "SET", "k" + i, VALUE);
            if (reply instanceof RespValue.Error) {
                refused = reply;
            }
        }
        assertTrue(refused != null, "expected an OOM error");
        assertTrue(((RespValue.Error) refused).message().startsWith("OOM"));
        assertEquals("PONG", ((RespValue.SimpleString) run(registry, client, "PING")).value());
    }

    @Test
    void unlimitedMemoryNeverEvicts() {
        ServerState state = new ServerState(16, null, System::currentTimeMillis);
        CommandRegistry registry = CommandRegistry.withDefaults(state);
        ClientConnection client = ClientConnection.detached(1);
        for (int i = 0; i < 50; i++) {
            run(registry, client, "SET", "k" + i, VALUE);
        }
        assertEquals(50L, ((RespValue.Integer) run(registry, client, "DBSIZE")).value());
    }
}
