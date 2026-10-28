package dev.hamid.redisj.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MemorySizeTest {

    @Test
    void parsesBareBytes() {
        assertEquals(0L, MemorySize.parse("0"));
        assertEquals(100L, MemorySize.parse("100"));
        assertEquals(7L, MemorySize.parse("7b"));
    }

    @Test
    void decimalAndBinaryUnitsDiffer() {
        assertEquals(1_000L, MemorySize.parse("1k"));
        assertEquals(1_024L, MemorySize.parse("1kb"));
        assertEquals(1_000_000L, MemorySize.parse("1m"));
        assertEquals(1_048_576L, MemorySize.parse("1MB"));
        assertEquals(2_147_483_648L, MemorySize.parse("2GB"));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> MemorySize.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> MemorySize.parse("-1"));
        assertThrows(IllegalArgumentException.class, () -> MemorySize.parse(""));
        assertThrows(IllegalArgumentException.class, () -> MemorySize.parse("1tb"));
        assertThrows(IllegalArgumentException.class, () -> MemorySize.parse(null));
    }

    @Test
    void rejectsOverflow() {
        assertThrows(IllegalArgumentException.class, () -> MemorySize.parse("99999999999999999g"));
    }
}
