package dev.hamid.redisj.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class NumbersTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void acceptsCanonicalIntegers() {
        assertEquals(0L, Numbers.parseLong(b("0")));
        assertEquals(-1L, Numbers.parseLong(b("-1")));
        assertEquals(Long.MAX_VALUE, Numbers.parseLong(b("9223372036854775807")));
        assertEquals(Long.MIN_VALUE, Numbers.parseLong(b("-9223372036854775808")));
    }

    @Test
    void rejectsNonCanonicalIntegers() {
        for (String text : new String[] {"", "-", "+1", "01", "-0", " 1", "1 ", "1a", "9223372036854775808"}) {
            OptionalLong value = Numbers.tryParseLong(b(text));
            assertTrue(value.isEmpty(), "should reject: '" + text + "'");
        }
        assertThrows(CommandException.class, () -> Numbers.parseLong(b("abc")));
    }

    @Test
    void parsesFloatsAndRejectsSpecialValues() {
        assertEquals(1.5, Numbers.parseDouble(b("1.5")));
        assertEquals(-2000.0, Numbers.parseDouble(b("-2e3")));
        assertEquals(0.5, Numbers.parseDouble(b(".5")));
        assertEquals(5.0, Numbers.parseDouble(b("5.")));
        for (String text : new String[] {"nan", "inf", "1f", "0x10", ""}) {
            assertThrows(CommandException.class, () -> Numbers.parseDouble(b(text)), text);
        }
    }
}
