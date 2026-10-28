package dev.hamid.redisj.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsMatchRedisConventions() {
        ServerConfig c = ServerConfig.defaults();
        assertEquals(6379, c.port());
        assertEquals("127.0.0.1", c.bind());
        assertEquals(16, c.databases());
        assertEquals(0L, c.maxMemory());
        assertFalse(c.appendOnly());
        assertEquals(0, c.timeoutSeconds());
        assertFalse(c.requirePass().isPresent());
    }

    @Test
    void builderRejectsInvalidValues() {
        ServerConfig.Builder b = ServerConfig.builder();
        assertThrows(IllegalArgumentException.class, () -> b.port(0));
        assertThrows(IllegalArgumentException.class, () -> b.port(65536));
        assertThrows(IllegalArgumentException.class, () -> b.databases(0));
        assertThrows(IllegalArgumentException.class, () -> b.databases(ServerConfig.MAX_DATABASES + 1));
        assertThrows(IllegalArgumentException.class, () -> b.bind("  "));
        assertThrows(IllegalArgumentException.class, () -> b.maxMemory(-1));
        assertThrows(IllegalArgumentException.class, () -> b.timeoutSeconds(-5));
    }

    @Test
    void emptyPasswordDisablesAuth() {
        ServerConfig c = ServerConfig.builder().requirePass("").build();
        assertFalse(c.requirePass().isPresent());
        assertEquals("s3cret", ServerConfig.builder().requirePass("s3cret").build().requirePass().orElseThrow());
    }

    @Test
    void toStringDoesNotLeakPassword() {
        ServerConfig c = ServerConfig.builder().requirePass("topsecret").build();
        assertFalse(c.toString().contains("topsecret"));
        assertTrue(c.toString().contains("<redacted>"));
    }

    @Test
    void toBuilderRoundTripsAndAllowsOverrides() {
        ServerConfig base = ServerConfig.builder().port(7000).databases(4).appendOnly(true).build();
        ServerConfig derived = base.toBuilder().port(7001).build();
        assertEquals(7001, derived.port());
        assertEquals(4, derived.databases());
        assertTrue(derived.appendOnly());
        assertEquals(7000, base.port());
    }

    @Test
    void loadReadsFileFromDisk() throws Exception {
        Path file = tempDir.resolve("redisj.conf");
        Files.writeString(file, "port 7100\nbind 0.0.0.0\n");
        ServerConfig c = ServerConfig.load(file);
        assertEquals(7100, c.port());
        assertEquals("0.0.0.0", c.bind());
    }
}
