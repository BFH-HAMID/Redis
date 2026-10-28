package dev.hamid.redisj.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hamid.redisj.protocol.RespValue.BulkString;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RespParserTest {

    private static ByteBuffer wrap(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
    }

    private static RespValue command(String... args) {
        List<RespValue> items = new ArrayList<>();
        for (String arg : args) {
            items.add(BulkString.of(arg));
        }
        return new RespValue.Array(items);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static List<RespValue> parseAll(RespParser parser, ByteBuffer buffer) {
        List<RespValue> out = new ArrayList<>();
        RespValue value;
        while ((value = parser.parse(buffer)) != null) {
            out.add(value);
        }
        return out;
    }

    /** Delivers {@code data} in fixed-size chunks, compacting between parses, as a socket reader would. */
    private static List<RespValue> feedChunks(RespParser parser, byte[] data, int chunk) {
        List<RespValue> out = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.allocate(data.length + 16);
        for (int offset = 0; offset < data.length; offset += chunk) {
            int end = Math.min(data.length, offset + chunk);
            buffer.put(data, offset, end - offset);
            buffer.flip();
            out.addAll(parseAll(parser, buffer));
            buffer.compact();
        }
        return out;
    }

    @Test
    void parsesRespTwoScalars() {
        RespParser p = new RespParser();
        assertEquals(new RespValue.SimpleString("OK"), p.parse(wrap("+OK\r\n")));
        assertEquals(new RespValue.Error("ERR boom"), p.parse(wrap("-ERR boom\r\n")));
        assertEquals(new RespValue.Integer(-42), p.parse(wrap(":-42\r\n")));
        assertEquals(BulkString.of("hello"), p.parse(wrap("$5\r\nhello\r\n")));
        assertEquals(RespValue.Null.INSTANCE, p.parse(wrap("$-1\r\n")));
        assertEquals(RespValue.Null.INSTANCE, p.parse(wrap("*-1\r\n")));
    }

    @Test
    void parsesNestedAndEmptyArrays() {
        RespParser p = new RespParser();
        RespValue nested = p.parse(wrap("*2\r\n*1\r\n:1\r\n$2\r\nab\r\n"));
        assertEquals(new RespValue.Array(List.<RespValue>of(
                new RespValue.Array(List.<RespValue>of(new RespValue.Integer(1))),
                BulkString.of("ab"))), nested);
        assertEquals(new RespValue.Array(List.of()), p.parse(wrap("*0\r\n")));
    }

    @Test
    void parsesRespThreeValues() {
        RespParser p = new RespParser();
        assertEquals(RespValue.Null.INSTANCE, p.parse(wrap("_\r\n")));
        assertEquals(new RespValue.Boolean(true), p.parse(wrap("#t\r\n")));
        assertEquals(new RespValue.Boolean(false), p.parse(wrap("#f\r\n")));
        assertEquals(new RespValue.Double(1.5), p.parse(wrap(",1.5\r\n")));
        assertEquals(new RespValue.Double(Double.POSITIVE_INFINITY), p.parse(wrap(",inf\r\n")));
        assertEquals(new RespValue.Double(Double.NEGATIVE_INFINITY), p.parse(wrap(",-inf\r\n")));
        assertEquals(new RespValue.Double(Double.NaN), p.parse(wrap(",nan\r\n")));
        assertEquals(new RespValue.Double(1e10), p.parse(wrap(",1e10\r\n")));

        RespValue map = p.parse(wrap("%1\r\n+a\r\n:1\r\n"));
        assertEquals(new RespValue.Map(java.util.Map.of(new RespValue.SimpleString("a"),
                new RespValue.Integer(1))), map);

        RespValue set = p.parse(wrap("~2\r\n$1\r\nx\r\n$1\r\ny\r\n"));
        assertEquals(new RespValue.Set(java.util.Set.of(BulkString.of("x"), BulkString.of("y"))), set);
    }

    @Test
    void parsesBinarySafeBulkStrings() {
        byte[] payload = {0, '\r', '\n', (byte) 0xFF, '$', '*', 'x'};
        byte[] wire = concat("$7\r\n".getBytes(StandardCharsets.US_ASCII), payload,
                "\r\n".getBytes(StandardCharsets.US_ASCII));
        RespValue value = new RespParser().parse(ByteBuffer.wrap(wire));
        assertArrayEquals(payload, ((BulkString) value).bytes());
    }

    @Test
    void emptyBulkStringIsNotNull() {
        assertEquals(BulkString.of(""), new RespParser().parse(wrap("$0\r\n\r\n")));
    }

    @Test
    void incompleteElementLeavesBufferPositionUntouched() {
        RespParser p = new RespParser();
        ByteBuffer buffer = wrap("$5\r\nab");
        assertNull(p.parse(buffer));
        assertEquals(0, buffer.position());
        assertFalse(p.inFrame());
    }

    @Test
    void partialFramesDecodeIdenticallyForEveryChunkSize() {
        String wire = "*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n+OK\r\n:7\r\n*2\r\n%1\r\n+k\r\n,2.5\r\n";
        byte[] data = wire.getBytes(StandardCharsets.UTF_8);
        List<RespValue> expected = parseAll(new RespParser(), ByteBuffer.wrap(data));
        assertEquals(3, expected.size());
        for (int chunk = 1; chunk <= data.length; chunk++) {
            assertEquals(expected.subList(0, 3) , feedChunks(new RespParser(), data, chunk).subList(0, 3),
                    "chunk size " + chunk);
        }
    }

    @Test
    void partialAggregateKeepsStateAcrossCalls() {
        RespParser p = new RespParser();
        ByteBuffer buffer = ByteBuffer.allocate(64);
        buffer.put("*2\r\n$3\r\nfoo\r\n".getBytes(StandardCharsets.UTF_8)).flip();
        assertNull(p.parse(buffer));
        assertTrue(p.inFrame());
        buffer.compact();
        buffer.put("$3\r\nbar\r\n".getBytes(StandardCharsets.UTF_8)).flip();
        assertEquals(command("foo", "bar"), p.parse(buffer));
        assertFalse(p.inFrame());
    }

    @Test
    void parsesInlineCommands() {
        RespParser p = new RespParser();
        assertEquals(command("PING"), p.parse(wrap("PING\r\n")));
        assertEquals(command("SET", "k", "a b"), p.parse(wrap("SET k \"a b\"\n")));
        assertEquals(command("GET", "it s"), p.parse(wrap("GET 'it s'\n")));
        assertEquals(command("SET", "k", "AB"), p.parse(wrap("SET k \"\\x41\\x42\"\r\n")));
        assertEquals(command("ECHO", "hi"), p.parse(wrap("\r\n\nECHO hi\r\n")));
    }

    @Test
    void inlineCommandsSplitAcrossReads() {
        byte[] data = "SET a \"x y\"\r\nGET a\r\n".getBytes(StandardCharsets.UTF_8);
        List<RespValue> values = feedChunks(new RespParser(), data, 1);
        assertEquals(List.of(command("SET", "a", "x y"), command("GET", "a")), values);
    }

    @Test
    void rejectsInlineInsideAggregate() {
        assertThrows(RespProtocolException.class, () -> new RespParser().parse(wrap("*1\r\nPING\r\n")));
    }

    @Test
    void rejectsUnbalancedInlineQuotes() {
        RespProtocolException e = assertThrows(RespProtocolException.class,
                () -> new RespParser().parse(wrap("SET k \"oops\n")));
        assertTrue(e.getMessage().contains("unbalanced quotes"));
    }

    @Test
    void enforcesBulkLengthLimit() {
        RespParser small = new RespParser(new RespParser.Limits(4, 1024, 1024, 8));
        assertThrows(RespProtocolException.class, () -> small.parse(wrap("$5\r\nhello\r\n")));

        RespParser defaults = new RespParser();
        assertThrows(RespProtocolException.class, () -> defaults.parse(wrap("$536870913\r\n")));
        // Exactly 512 MB is accepted as a header; the parser waits for the payload without allocating it.
        assertNull(defaults.parse(wrap("$536870912\r\n")));
    }

    @Test
    void handlesOneMegabytePayloadInChunks() {
        byte[] payload = new byte[1 << 20];
        new Random(42).nextBytes(payload);
        byte[] wire = new RespWriter().toBytes(new RespValue.BulkString(payload));
        List<RespValue> values = feedChunks(new RespParser(), wire, 65536);
        assertEquals(1, values.size());
        assertArrayEquals(payload, ((BulkString) values.get(0)).bytes());
    }

    @Test
    void enforcesLineLengthLimit() {
        RespParser p = new RespParser(new RespParser.Limits(1024, 1024, 16, 8));
        assertThrows(RespProtocolException.class, () -> p.parse(wrap("+" + "a".repeat(25))));
        assertThrows(RespProtocolException.class, () -> p.parse(wrap("A".repeat(30) + "\r\n")));
    }

    @Test
    void enforcesNestingDepth() {
        RespParser p = new RespParser(new RespParser.Limits(1024, 1024, 1024, 2));
        assertThrows(RespProtocolException.class, () -> p.parse(wrap("*1\r\n*1\r\n*1\r\n:1\r\n")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "$abc\r\n",
            "*-5\r\n",
            "$3\r\nabcde\r\n",
            ":12x\r\n",
            "_x\r\n",
            "#x\r\n",
            "+OK\n",
            ",1.2.3\r\n",
            "*1\r\n$-7\r\n",
            "%-1\r\n",
            "(123\r\n",
            "*1\r\n+x\n",
            "*1\r\n:\r\n",
            "$\r\n",
            "*1\r\n=15\r\n"
    })
    void rejectsMalformedInput(String wire) {
        assertThrows(RespProtocolException.class, () -> new RespParser().parse(wrap(wire)));
    }

    @Test
    void pipelineOfOneThousandCommands() {
        RespWriter writer = new RespWriter();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        for (int i = 0; i < 1000; i++) {
            wire.writeBytes(writer.toBytes(command("SET", "key:" + i, "value:" + i)));
        }
        byte[] data = wire.toByteArray();

        List<RespValue> all = parseAll(new RespParser(), ByteBuffer.wrap(data));
        assertEquals(1000, all.size());
        assertEquals(command("SET", "key:0", "value:0"), all.get(0));
        assertEquals(command("SET", "key:999", "value:999"), all.get(999));
        assertEquals(all, feedChunks(new RespParser(), data, 7));
    }
}
