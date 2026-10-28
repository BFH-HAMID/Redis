package dev.hamid.redisj.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hamid.redisj.protocol.RespValue;
import dev.hamid.redisj.server.ClientConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StreamCommandsTest {

    private CommandRegistry registry;
    private ClientConnection client;

    @BeforeEach
    void setUp() {
        registry = CommandRegistry.withDefaults(new ServerState(16, null, System::currentTimeMillis));
        client = ClientConnection.detached(1);
    }

    private RespValue run(String... parts) {
        List<RespValue> elements = new ArrayList<>(parts.length);
        for (String part : parts) {
            elements.add(new RespValue.BulkString(part.getBytes(StandardCharsets.UTF_8)));
        }
        return registry.handle(client, new RespValue.Array(elements));
    }

    private static String text(RespValue value) {
        return value instanceof RespValue.BulkString bulk
                ? new String(bulk.bytes(), StandardCharsets.UTF_8) : null;
    }

    private static List<RespValue> items(RespValue value) {
        return ((RespValue.Array) value).elements();
    }

    /** The IDs in a range reply, in order. */
    private static List<String> ids(RespValue range) {
        List<String> out = new ArrayList<>();
        for (RespValue entry : items(range)) {
            out.add(text(items(entry).get(0)));
        }
        return out;
    }

    private static long integer(RespValue value) {
        return ((RespValue.Integer) value).value();
    }

    @Test
    void xaddWithExplicitIdsRejectsNonIncreasingIds() {
        assertEquals("1-1", text(run("XADD", "s", "1-1", "f", "a")));
        assertEquals("2-0", text(run("XADD", "s", "2-0", "f", "b")));
        RespValue error = run("XADD", "s", "2-0", "f", "c");
        assertInstanceOf(RespValue.Error.class, error);
        assertTrue(((RespValue.Error) error).message().startsWith("ERR The ID specified in XADD is equal"));
        assertEquals(2, integer(run("XLEN", "s")));
    }

    @Test
    void autoIdsAreIncreasingAndPartialWildcardWorks() {
        String first = text(run("XADD", "s", "*", "f", "a"));
        String second = text(run("XADD", "s", "*", "f", "b"));
        assertEquals(List.of(first, second), ids(run("XRANGE", "s", "-", "+")));
        assertTrue(!first.equals(second));
        assertEquals("5-0", text(run("XADD", "t", "5-*", "f", "a")));
        assertEquals("5-1", text(run("XADD", "t", "5-*", "f", "b")));
    }

    @Test
    void xrangeAndXrevrangeRespectBoundsAndCount() {
        run("XADD", "s", "1-1", "f", "a");
        run("XADD", "s", "2-0", "f", "b");
        run("XADD", "s", "3-0", "f", "c");
        assertEquals(List.of("1-1", "2-0", "3-0"), ids(run("XRANGE", "s", "-", "+")));
        assertEquals(List.of("2-0"), ids(run("XRANGE", "s", "2", "2")));
        assertEquals(List.of("2-0", "3-0"), ids(run("XRANGE", "s", "2", "+")));
        assertEquals(List.of("3-0", "2-0"), ids(run("XREVRANGE", "s", "+", "-", "COUNT", "2")));
    }

    @Test
    void xdelAndXtrimRemoveEntries() {
        run("XADD", "s", "1-1", "f", "a");
        run("XADD", "s", "2-0", "f", "b");
        run("XADD", "s", "3-0", "f", "c");
        assertEquals(1, integer(run("XDEL", "s", "2-0")));
        assertEquals(0, integer(run("XDEL", "s", "9-9")));
        assertEquals(1, integer(run("XTRIM", "s", "MAXLEN", "1")));
        assertEquals(List.of("3-0"), ids(run("XRANGE", "s", "-", "+")));
    }

    @Test
    void maxlenOptionOnXaddTrimsOldest() {
        run("XADD", "s", "MAXLEN", "2", "1-1", "f", "a");
        run("XADD", "s", "MAXLEN", "2", "2-0", "f", "b");
        run("XADD", "s", "MAXLEN", "2", "3-0", "f", "c");
        assertEquals(List.of("2-0", "3-0"), ids(run("XRANGE", "s", "-", "+")));
    }

    @Test
    void lastIdSurvivesDeletionSoIdsKeepIncreasing() {
        run("XADD", "s", "5-0", "f", "a");
        run("XDEL", "s", "5-0");
        assertInstanceOf(RespValue.Error.class, run("XADD", "s", "4-0", "f", "b"));
    }

    @Test
    void xreadReturnsEntriesAfterGivenId() {
        run("XADD", "s", "1-1", "f", "a");
        run("XADD", "s", "2-0", "f", "b");
        RespValue reply = run("XREAD", "STREAMS", "s", "1-1");
        RespValue stream = items(reply).get(0);
        assertEquals("s", text(items(stream).get(0)));
        assertEquals(List.of("2-0"), ids(items(stream).get(1)));
        assertInstanceOf(RespValue.Null.class, run("XREAD", "STREAMS", "s", "$"));
    }

    @Test
    void xreadRejectsBlock() {
        RespValue error = run("XREAD", "BLOCK", "100", "STREAMS", "s", "0");
        assertInstanceOf(RespValue.Error.class, error);
    }

    @Test
    void streamCommandsRefuseOtherTypes() {
        run("SET", "k", "v");
        assertTrue(((RespValue.Error) run("XADD", "k", "*", "f", "v")).message().startsWith("WRONGTYPE"));
        assertEquals(0, integer(run("XLEN", "missing")));
    }
}
