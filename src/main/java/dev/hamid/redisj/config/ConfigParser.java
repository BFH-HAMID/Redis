package dev.hamid.redisj.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parser for the {@code redisj.conf} format.
 *
 * <p>The format mirrors {@code redis.conf}: one directive per line, a directive name
 * followed by its arguments, {@code #} starts a comment, and arguments containing
 * whitespace may be wrapped in double or single quotes. Double-quoted strings support
 * the escapes {@code \\n}, {@code \\r}, {@code \\t}, {@code \\"} and {@code \\\\}.
 * Directive names are case-insensitive. Unknown directives are rejected.
 *
 * <p>Supported directives: {@code port}, {@code bind}, {@code requirepass},
 * {@code appendonly}, {@code maxmemory}, {@code databases}, {@code timeout}.
 */
public final class ConfigParser {

    private ConfigParser() {
    }

    /**
     * Parses configuration text using default values for anything not specified.
     *
     * @param text the complete contents of a configuration file
     * @return the parsed configuration
     * @throws ConfigException if any line is malformed or contains an invalid value
     */
    public static ServerConfig parse(String text) {
        return applyAll(ServerConfig.builder(), text).build();
    }

    /**
     * Applies every directive in {@code text} to the given builder.
     *
     * @param builder the builder to modify
     * @param text    configuration text
     * @return the same builder, for chaining
     * @throws ConfigException if any line is malformed or contains an invalid value
     */
    public static ServerConfig.Builder applyAll(ServerConfig.Builder builder, String text) {
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            List<String> tokens = tokenize(lines[i], lineNo);
            if (!tokens.isEmpty()) {
                applyDirective(builder, tokens, lineNo);
            }
        }
        return builder;
    }

    /**
     * Splits one line into tokens, honouring quotes and comments.
     *
     * @param line   the raw line
     * @param lineNo one-based line number, used in error messages
     * @return the tokens; empty for blank or comment-only lines
     */
    static List<String> tokenize(String line, int lineNo) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '#') {
                break;
            } else if (c == '"' || c == '\'') {
                i = readQuoted(line, i, lineNo, out);
            } else {
                int start = i;
                while (i < n && !Character.isWhitespace(line.charAt(i))) {
                    i++;
                }
                out.add(line.substring(start, i));
            }
        }
        return out;
    }

    private static int readQuoted(String line, int start, int lineNo, List<String> out) {
        char quote = line.charAt(start);
        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == quote) {
                i++;
                if (i < line.length() && !Character.isWhitespace(line.charAt(i))) {
                    throw lineError(lineNo, "closing quote must be followed by a space");
                }
                out.add(sb.toString());
                return i;
            }
            if (c == '\\' && quote == '"' && i + 1 < line.length()) {
                char escaped = line.charAt(++i);
                sb.append(switch (escaped) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> escaped;
                });
            } else {
                sb.append(c);
            }
            i++;
        }
        throw lineError(lineNo, "unbalanced quotes");
    }

    private static void applyDirective(ServerConfig.Builder b, List<String> tokens, int lineNo) {
        String name = tokens.get(0).toLowerCase(Locale.ROOT);
        List<String> args = tokens.subList(1, tokens.size());
        try {
            switch (name) {
                case "port" -> b.port(parseInt(name, single(name, args)));
                case "bind" -> b.bind(single(name, args));
                case "requirepass" -> b.requirePass(single(name, args));
                case "appendonly" -> b.appendOnly(parseYesNo(name, single(name, args)));
                case "maxmemory" -> b.maxMemory(MemorySize.parse(single(name, args)));
                case "databases" -> b.databases(parseInt(name, single(name, args)));
                case "timeout" -> b.timeoutSeconds(parseInt(name, single(name, args)));
                default -> throw new IllegalArgumentException("Bad directive \"" + name + "\"");
            }
        } catch (IllegalArgumentException e) {
            throw lineError(lineNo, e.getMessage());
        }
    }

    private static String single(String name, List<String> args) {
        if (args.size() != 1) {
            throw new IllegalArgumentException(name + " expects exactly one argument");
        }
        return args.get(0);
    }

    private static int parseInt(String name, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer: " + value, e);
        }
    }

    private static boolean parseYesNo(String name, String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "yes" -> true;
            case "no" -> false;
            default -> throw new IllegalArgumentException(name + " must be yes or no: " + value);
        };
    }

    private static ConfigException lineError(int lineNo, String message) {
        return new ConfigException("line " + lineNo + ": " + message);
    }
}
