package dev.hamid.redisj.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigParserTest {

    @Test
    void parsesFullFileWithCommentsAndBlankLines() {
        String text = """
                # a comment
                port 7000

                bind 0.0.0.0   # trailing comment
                requirepass "p@ss word"
                appendonly yes
                maxmemory 256mb
                databases 4
                timeout 30
                """;
        ServerConfig c = ConfigParser.parse(text);
        assertEquals(7000, c.port());
        assertEquals("0.0.0.0", c.bind());
        assertEquals("p@ss word", c.requirePass().orElseThrow());
        assertTrue(c.appendOnly());
        assertEquals(256L * 1024 * 1024, c.maxMemory());
        assertEquals(4, c.databases());
        assertEquals(30, c.timeoutSeconds());
    }

    @Test
    void directiveNamesAreCaseInsensitive() {
        assertEquals(7777, ConfigParser.parse("PORT 7777").port());
    }

    @Test
    void appendOnlyAcceptsNoAndRejectsGarbage() {
        assertFalse(ConfigParser.parse("appendonly no").appendOnly());
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigParser.parse("appendonly maybe"));
        assertTrue(e.getMessage().startsWith("line 1:"));
    }

    @Test
    void unknownDirectiveReportsLineNumber() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigParser.parse("port 6379\nfrobnicate 1\n"));
        assertEquals("line 2: Bad directive \"frobnicate\"", e.getMessage());
    }

    @Test
    void invalidPortIsReported() {
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigParser.parse("port abc"));
        assertTrue(e.getMessage().contains("port must be an integer"));
        assertThrows(ConfigException.class, () -> ConfigParser.parse("port 70000"));
    }

    @Test
    void wrongArgumentCountIsRejected() {
        assertThrows(ConfigException.class, () -> ConfigParser.parse("port 1 2"));
        assertThrows(ConfigException.class, () -> ConfigParser.parse("port"));
    }

    @Test
    void unbalancedQuotesAreRejected() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigParser.parse("requirepass \"oops"));
        assertEquals("line 1: unbalanced quotes", e.getMessage());
    }

    @Test
    void tokenizerHandlesEscapesAndSingleQuotes() {
        assertEquals(List.of("a\"b"), ConfigParser.tokenize("\"a\\\"b\"", 1));
        assertEquals(List.of("tab\there"), ConfigParser.tokenize("\"tab\\there\"", 1));
        assertEquals(List.of("it s"), ConfigParser.tokenize("'it s'", 1));
        assertEquals(List.of("a\\nb"), ConfigParser.tokenize("'a\\nb'", 1));
        assertEquals(List.of("a", "b"), ConfigParser.tokenize("a   b # rest", 1));
        assertEquals(List.of(), ConfigParser.tokenize("   # only a comment", 1));
    }

    @Test
    void emptyTextYieldsDefaults() {
        assertEquals(ServerConfig.defaults().port(), ConfigParser.parse("").port());
    }
}
