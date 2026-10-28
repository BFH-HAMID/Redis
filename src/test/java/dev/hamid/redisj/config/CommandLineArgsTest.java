package dev.hamid.redisj.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CommandLineArgsTest {

    @Test
    void noArgumentsMeansNoOverrides() {
        CommandLineArgs.Options o = CommandLineArgs.parse();
        assertNull(o.port());
        assertNull(o.configFile());
        assertFalse(o.help());
    }

    @Test
    void parsesPortInBothForms() {
        assertEquals(6380, CommandLineArgs.parse("--port", "6380").port());
        assertEquals(6381, CommandLineArgs.parse("--port=6381").port());
    }

    @Test
    void parsesConfigInBothFormsAndPositional() {
        assertEquals(Path.of("a.conf"), CommandLineArgs.parse("--config", "a.conf").configFile());
        assertEquals(Path.of("b.conf"), CommandLineArgs.parse("--config=b.conf").configFile());
        assertEquals(Path.of("c.conf"), CommandLineArgs.parse("c.conf").configFile());
    }

    @Test
    void combinesOptions() {
        CommandLineArgs.Options o = CommandLineArgs.parse("--config", "x.conf", "--port", "7000");
        assertEquals(7000, o.port());
        assertEquals(Path.of("x.conf"), o.configFile());
    }

    @Test
    void recognisesHelp() {
        assertTrue(CommandLineArgs.parse("-h").help());
        assertTrue(CommandLineArgs.parse("--help").help());
    }

    @Test
    void rejectsUnknownOptionsAndMissingValues() {
        assertThrows(IllegalArgumentException.class, () -> CommandLineArgs.parse("--bogus"));
        assertThrows(IllegalArgumentException.class, () -> CommandLineArgs.parse("--port"));
        assertThrows(IllegalArgumentException.class, () -> CommandLineArgs.parse("--port", "http"));
        assertThrows(IllegalArgumentException.class, () -> CommandLineArgs.parse("a.conf", "b.conf"));
    }
}
