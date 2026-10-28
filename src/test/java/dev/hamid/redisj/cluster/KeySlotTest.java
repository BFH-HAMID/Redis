package dev.hamid.redisj.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class KeySlotTest {

    @Test
    void crc16MatchesTheXmodemCheckValue() {
        assertEquals(0x31C3, KeySlot.crc16("123456789".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void keySlotMatchesTheRedisClusterDocumentation() {
        assertEquals(12182, KeySlot.of("foo"));
    }

    @Test
    void hashTagsSelectTheSlot() {
        assertEquals(KeySlot.of("{user1000}.following"), KeySlot.of("{user1000}.followers"));
        assertEquals(KeySlot.of("user1000"), KeySlot.of("{user1000}"));
    }

    @Test
    void emptyOrUnclosedTagsHashTheWholeKey() {
        assertEquals(KeySlot.of("foo{}bar"), KeySlot.crc16("foo{}bar".getBytes(StandardCharsets.US_ASCII))
                % KeySlot.SLOTS);
        assertEquals(KeySlot.of("foo{bar"), KeySlot.crc16("foo{bar".getBytes(StandardCharsets.US_ASCII))
                % KeySlot.SLOTS);
    }
}
