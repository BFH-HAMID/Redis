package dev.hamid.redisj.command;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The arguments of one command, including the command name at index 0.
 *
 * <p>Keys are returned as strings in ISO-8859-1, which maps each byte to one char and keeps keys
 * binary safe. Option names are matched case-insensitively with {@link #upper(int)}.
 */
public final class Args {

    private final List<byte[]> items;

    /**
     * @param items the raw argument bytes; the list is copied
     */
    public Args(List<byte[]> items) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
    }

    /**
     * @return the raw argument bytes, in order, including the command name; unmodifiable
     */
    public List<byte[]> items() {
        return items;
    }

    /**
     * @return the number of arguments, including the command name
     */
    public int size() {
        return items.size();
    }

    /**
     * @param index the argument index
     * @return the raw bytes of the argument
     */
    public byte[] bytes(int index) {
        return items.get(index);
    }

    /**
     * @param index the argument index
     * @return the argument as a key string (ISO-8859-1)
     */
    public String key(int index) {
        return new String(items.get(index), StandardCharsets.ISO_8859_1);
    }

    /**
     * @param index the argument index
     * @return the argument decoded as UTF-8
     */
    public String text(int index) {
        return new String(items.get(index), StandardCharsets.UTF_8);
    }

    /**
     * @param index the argument index
     * @return the argument decoded as UTF-8 and upper-cased with the root locale
     */
    public String upper(int index) {
        return text(index).toUpperCase(Locale.ROOT);
    }

    /**
     * @param index the argument index
     * @return the argument as a strict 64-bit integer
     * @throws CommandException if the argument is not an integer
     */
    public long longValue(int index) {
        return Numbers.parseLong(items.get(index));
    }

    /**
     * @param index the argument index
     * @return the argument as a floating point number
     * @throws CommandException if the argument is not a float
     */
    public double doubleValue(int index) {
        return Numbers.parseDouble(items.get(index));
    }
}
