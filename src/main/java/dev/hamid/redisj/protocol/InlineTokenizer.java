package dev.hamid.redisj.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits an inline command line into arguments, following Redis's {@code sdssplitargs}.
 *
 * <p>Arguments are separated by whitespace. Double quotes support the escapes {@code \n},
 * {@code \r}, {@code \t}, {@code \b}, {@code \a}, {@code \xHH} and backslash-escaped
 * characters. Single quotes support only {@code \'}. A closing quote must be followed by
 * whitespace or the end of the line.
 */
final class InlineTokenizer {

    private static final int NORMAL = 0;
    private static final int DOUBLE = 1;
    private static final int SINGLE = 2;
    private static final String UNBALANCED = "Protocol error: unbalanced quotes in request";

    private InlineTokenizer() {
    }

    /**
     * Tokenizes one line (without its line terminator).
     *
     * @param line the raw bytes of the line
     * @return the arguments; empty if the line holds only whitespace
     * @throws RespProtocolException if quotes are unbalanced
     */
    static List<byte[]> split(byte[] line) {
        List<byte[]> tokens = new ArrayList<>();
        int i = 0;
        int n = line.length;
        while (true) {
            while (i < n && isSpace(line[i])) {
                i++;
            }
            if (i >= n) {
                return tokens;
            }
            ByteArrayOutputStream token = new ByteArrayOutputStream();
            int state = NORMAL;
            while (true) {
                if (i >= n) {
                    if (state != NORMAL) {
                        throw new RespProtocolException(UNBALANCED);
                    }
                    break;
                }
                byte c = line[i];
                if (state == NORMAL) {
                    if (isSpace(c)) {
                        break;
                    }
                    if (c == '"') {
                        state = DOUBLE;
                    } else if (c == '\'') {
                        state = SINGLE;
                    } else {
                        token.write(c);
                    }
                    i++;
                } else if (state == DOUBLE) {
                    if (c == '"') {
                        state = NORMAL;
                        i++;
                        requireSpaceOrEnd(line, i);
                    } else {
                        i = readDoubleQuotedUnit(line, i, token);
                    }
                } else {
                    if (c == '\\' && i + 1 < n && line[i + 1] == '\'') {
                        token.write('\'');
                        i += 2;
                    } else if (c == '\'') {
                        state = NORMAL;
                        i++;
                        requireSpaceOrEnd(line, i);
                    } else {
                        token.write(c);
                        i++;
                    }
                }
            }
            tokens.add(token.toByteArray());
        }
    }

    /**
     * Reads one character or escape sequence inside double quotes, starting at {@code i}.
     *
     * @return the index just after the consumed unit
     */
    private static int readDoubleQuotedUnit(byte[] line, int i, ByteArrayOutputStream token) {
        int n = line.length;
        byte c = line[i];
        if (c == '\\' && i + 1 < n) {
            if (line[i + 1] == 'x' && i + 3 < n && hex(line[i + 2]) >= 0 && hex(line[i + 3]) >= 0) {
                token.write(hex(line[i + 2]) * 16 + hex(line[i + 3]));
                return i + 4;
            }
            char escaped = (char) line[i + 1];
            int mapped = switch (escaped) {
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'b' -> '\b';
                case 'a' -> 7;
                default -> escaped;
            };
            token.write(mapped);
            return i + 2;
        }
        token.write(c);
        return i + 1;
    }

    private static void requireSpaceOrEnd(byte[] line, int index) {
        if (index < line.length && !isSpace(line[index])) {
            throw new RespProtocolException(UNBALANCED);
        }
    }

    private static boolean isSpace(byte c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0b || c == 0x0c;
    }

    private static int hex(byte c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
