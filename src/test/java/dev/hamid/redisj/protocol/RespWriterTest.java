package dev.hamid.redisj.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hamid.redisj.protocol.RespValue.BulkString;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RespWriterTest {

    private static String encode(RespValue value, ProtocolVersion version) {
        return new String(new RespWriter(version).toBytes(value), StandardCharsets.UTF_8);
    }

    private static RespValue map(RespValue key, RespValue value) {
        Map<RespValue, RespValue> entries = new LinkedHashMap<>();
        entries.put(key, value);
        return new RespValue.Map(entries);
    }

    @Test
    void encodesRespTwoScalars() {
        RespWriter w = new RespWriter();
        assertEquals("+OK\r\n", encode(new RespValue.SimpleString("OK"), ProtocolVersion.RESP2));
        assertEquals("-ERR x\r\n", encode(new RespValue.Error("ERR x"), ProtocolVersion.RESP2));
        assertEquals(":-5\r\n", encode(new RespValue.Integer(-5), ProtocolVersion.RESP2));
        assertEquals("$2\r\nhi\r\n", encode(BulkString.of("hi"), ProtocolVersion.RESP2));
        assertEquals("$-1\r\n", encode(RespValue.Null.INSTANCE, ProtocolVersion.RESP2));
        assertEquals("*2\r\n:1\r\n$1\r\nz\r\n", new String(w.toBytes(
                new RespValue.Array(List.of(new RespValue.Integer(1), BulkString.of("z")))),
                StandardCharsets.UTF_8));
    }

    @Test
    void downgradesRespThreeKindsInRespTwo() {
        assertEquals("*2\r\n+a\r\n:1\r\n", encode(map(new RespValue.SimpleString("a"),
                new RespValue.Integer(1)), ProtocolVersion.RESP2));
        assertEquals("*2\r\n$1\r\nx\r\n$1\r\ny\r\n", encode(new RespValue.Set(
                new LinkedHashSet<RespValue>(List.of(BulkString.of("x"), BulkString.of("y")))), ProtocolVersion.RESP2));
        assertEquals(":1\r\n", encode(new RespValue.Boolean(true), ProtocolVersion.RESP2));
        assertEquals(":0\r\n", encode(new RespValue.Boolean(false), ProtocolVersion.RESP2));
        assertEquals("$3\r\n1.5\r\n", encode(new RespValue.Double(1.5), ProtocolVersion.RESP2));
        assertEquals("$3\r\ninf\r\n", encode(new RespValue.Double(Double.POSITIVE_INFINITY), ProtocolVersion.RESP2));
    }

    @Test
    void encodesRespThreeKindsNatively() {
        assertEquals("_\r\n", encode(RespValue.Null.INSTANCE, ProtocolVersion.RESP3));
        assertEquals("%1\r\n+a\r\n:1\r\n", encode(map(new RespValue.SimpleString("a"),
                new RespValue.Integer(1)), ProtocolVersion.RESP3));
        assertEquals("~1\r\n$1\r\nx\r\n", encode(new RespValue.Set(
                new LinkedHashSet<RespValue>(Set.of(BulkString.of("x")))), ProtocolVersion.RESP3));
        assertEquals("#t\r\n", encode(new RespValue.Boolean(true), ProtocolVersion.RESP3));
        assertEquals(",3\r\n", encode(new RespValue.Double(3.0), ProtocolVersion.RESP3));
        assertEquals(",-inf\r\n", encode(new RespValue.Double(Double.NEGATIVE_INFINITY), ProtocolVersion.RESP3));
        assertEquals(",nan\r\n", encode(new RespValue.Double(Double.NaN), ProtocolVersion.RESP3));
        assertEquals(",1e20\r\n", encode(new RespValue.Double(1e20), ProtocolVersion.RESP3));
    }

    @Test
    void respThreeValuesRoundTripThroughParser() {
        Map<RespValue, RespValue> entries = new LinkedHashMap<>();
        entries.put(new RespValue.SimpleString("list"), new RespValue.Array(List.of(
                new RespValue.Integer(-1), new RespValue.Double(2.5), new RespValue.Boolean(false),
                RespValue.Null.INSTANCE, BulkString.of("bin\r\n\0"))));
        entries.put(new RespValue.SimpleString("nan"), new RespValue.Double(Double.NaN));
        RespValue original = new RespValue.Map(entries);

        byte[] wire = new RespWriter(ProtocolVersion.RESP3).toBytes(original);
        assertEquals(original, new RespParser().parse(ByteBuffer.wrap(wire)));
    }

    @Test
    void simpleStringsAndErrorsCannotBreakFraming() {
        assertEquals("-a  b\r\n", encode(new RespValue.Error("a\r\nb"), ProtocolVersion.RESP2));
        assertEquals("+x  y\r\n", encode(new RespValue.SimpleString("x\ny"), ProtocolVersion.RESP2));
    }

    @Test
    void writeIsAllOrNothing() {
        RespWriter writer = new RespWriter();
        ByteBuffer tooSmall = ByteBuffer.allocate(4);
        assertFalse(writer.write(new RespValue.SimpleString("PONG"), tooSmall));
        assertEquals(0, tooSmall.position());

        ByteBuffer exact = ByteBuffer.allocate(writer.encodedLength(new RespValue.SimpleString("PONG")));
        assertTrue(writer.write(new RespValue.SimpleString("PONG"), exact));
        assertEquals(exact.capacity(), exact.position());
    }

    @Test
    void encodedLengthMatchesOutput() {
        RespWriter writer = new RespWriter();
        RespValue value = new RespValue.Array(List.of(BulkString.of("SET"), BulkString.of("k"),
                new RespValue.Integer(12345)));
        assertEquals(writer.toBytes(value).length, writer.encodedLength(value));
    }

    @Test
    void versionAccessors() {
        assertEquals(ProtocolVersion.RESP2, new RespWriter().version());
        assertEquals(3, ProtocolVersion.fromNumber(3).number());
        assertThrows(IllegalArgumentException.class, () -> ProtocolVersion.fromNumber(4));
    }
}
