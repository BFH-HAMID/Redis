package dev.hamid.redisj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RedisJApplicationTest {

    @TempDir
    Path tempDir;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    private int run(String... args) {
        return RedisJApplication.run(args, out, err);
    }

    private String stdout() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void printsBannerAndDefaultsWhenNoArguments() {
        assertEquals(0, run());
        assertTrue(stdout().contains("RedisJ " + RedisJApplication.VERSION));
        assertTrue(stdout().contains("port=6379"));
    }

    @Test
    void helpPrintsUsage() {
        assertEquals(0, run("--help"));
        assertTrue(stdout().contains("Usage:"));
    }

    @Test
    void portOptionOverridesDefault() {
        assertEquals(0, run("--port", "7001"));
        assertTrue(stdout().contains("port=7001"));
    }

    @Test
    void configFileIsLoadedAndPortOptionWins() throws Exception {
        Path conf = tempDir.resolve("redisj.conf");
        Files.writeString(conf, "port 7002\ndatabases 4\n");
        assertEquals(0, run("--config", conf.toString()));
        assertTrue(stdout().contains("port=7002"));
        assertTrue(stdout().contains("databases=4"));

        outBytes.reset();
        assertEquals(0, run("--config", conf.toString(), "--port", "7003"));
        assertTrue(stdout().contains("port=7003"));
    }

    @Test
    void invalidPortExitsWithError() {
        assertEquals(1, run("--port", "70000"));
        assertTrue(stderr().contains("port must be between 1 and 65535"));
    }

    @Test
    void missingConfigFileExitsWithError() {
        assertEquals(1, run("--config", tempDir.resolve("missing.conf").toString()));
        assertTrue(stderr().contains("cannot read config file"));
    }

    @Test
    void badDirectiveExitsWithError() throws Exception {
        Path conf = tempDir.resolve("bad.conf");
        Files.writeString(conf, "nonsense value\n");
        assertEquals(1, run("--config", conf.toString()));
        assertTrue(stderr().contains("line 1"));
    }
}
