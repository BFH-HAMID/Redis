package dev.hamid.redisj.command;

import dev.hamid.redisj.protocol.RespValue;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Factory helpers for the reply values that commands return most often.
 */
public final class Replies {

    private Replies() {
    }

    /**
     * @return {@code +OK}
     */
    public static RespValue ok() {
        return new RespValue.SimpleString("OK");
    }

    /**
     * @return the RESP null
     */
    public static RespValue nil() {
        return RespValue.Null.INSTANCE;
    }

    /**
     * @param value the integer
     * @return an integer reply
     */
    public static RespValue integer(long value) {
        return new RespValue.Integer(value);
    }

    /**
     * @param bytes the payload
     * @return a bulk string reply
     */
    public static RespValue bulk(byte[] bytes) {
        return new RespValue.BulkString(bytes);
    }

    /**
     * @param text the text, encoded as UTF-8
     * @return a bulk string reply
     */
    public static RespValue bulkText(String text) {
        return new RespValue.BulkString(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param items the elements
     * @return an array reply
     */
    public static RespValue array(List<RespValue> items) {
        return new RespValue.Array(items);
    }
}
