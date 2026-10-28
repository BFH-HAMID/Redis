package dev.hamid.redisj.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.hamid.redisj.config.ServerConfig;
import org.junit.jupiter.api.Test;

class ServerOptionsTest {

    @Test
    void ephemeralOptionsListenOnLoopbackPortZero() {
        ServerOptions o = ServerOptions.ephemeral();
        assertEquals(0, o.address().getPort());
        assertEquals(0, o.ioThreads());
        assertEquals(10_000, o.maxClients());
    }

    @Test
    void derivesFromServerConfig() throws Exception {
        ServerConfig config = ServerConfig.builder().port(7001).ioThreads(2).maxClients(5)
                .timeoutSeconds(30).requirePass("pw").build();
        ServerOptions o = ServerOptions.from(config);
        assertEquals(7001, o.address().getPort());
        assertEquals(2, o.ioThreads());
        assertEquals(5, o.maxClients());
        assertEquals(30, o.idleTimeoutSeconds());
        assertEquals("pw", o.requirePass());
    }

    @Test
    void withersReturnModifiedCopies() {
        ServerOptions base = ServerOptions.ephemeral();
        assertEquals(3, base.withIoThreads(3).ioThreads());
        assertEquals(7, base.withMaxClients(7).maxClients());
        assertEquals(9, base.withIdleTimeoutSeconds(9).idleTimeoutSeconds());
        assertEquals(0, base.ioThreads());
    }

    @Test
    void rejectsInvalidValues() {
        ServerOptions base = ServerOptions.ephemeral();
        assertThrows(IllegalArgumentException.class, () -> base.withIoThreads(-1));
        assertThrows(IllegalArgumentException.class, () -> base.withMaxClients(0));
        assertThrows(IllegalArgumentException.class, () -> base.withIdleTimeoutSeconds(-1));
        assertThrows(IllegalArgumentException.class, () -> new ServerOptions(base.address(), 0, 1, 0, null,
                1024, 512, 1024));
        assertThrows(IllegalArgumentException.class, () -> new ServerOptions(base.address(), 0, 1, 0, null,
                1024, 2048, 512));
    }
}
