package dev.hamid.redisj.config;

import java.nio.file.Path;

/**
 * Parser for RedisJ command-line options.
 *
 * <p>Supported forms: {@code --port 6380}, {@code --port=6380}, {@code --config file},
 * {@code --config=file}, a bare positional config path, and {@code -h}/{@code --help}.
 */
public final class CommandLineArgs {

    private CommandLineArgs() {
    }

    /**
     * Parsed command-line options. Absent values are {@code null}.
     *
     * @param port       the port given with {@code --port}, or {@code null}
     * @param configFile the configuration file path, or {@code null}
     * @param help       whether help was requested
     */
    public record Options(Integer port, Path configFile, boolean help) {
    }

    /**
     * Parses the given arguments.
     *
     * @param args the raw command-line arguments
     * @return the parsed options
     * @throws IllegalArgumentException if an option is unknown or misses its value
     */
    public static Options parse(String... args) {
        Integer port = null;
        Path configFile = null;
        boolean help = false;
        int i = 0;
        while (i < args.length) {
            String arg = args[i++];
            if (arg.equals("-h") || arg.equals("--help")) {
                help = true;
                continue;
            }
            String name = arg;
            String inlineValue = null;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 0) {
                name = arg.substring(0, eq);
                inlineValue = arg.substring(eq + 1);
            }
            if (name.equals("--port")) {
                String value = inlineValue != null ? inlineValue : requireValue(args, i++, name);
                port = parsePort(value);
            } else if (name.equals("--config")) {
                String value = inlineValue != null ? inlineValue : requireValue(args, i++, name);
                configFile = Path.of(value);
            } else if (name.startsWith("-")) {
                throw new IllegalArgumentException("unknown option: " + arg);
            } else {
                if (configFile != null) {
                    throw new IllegalArgumentException("unexpected argument: " + arg);
                }
                configFile = Path.of(arg);
            }
        }
        return new Options(port, configFile, help);
    }

    private static String requireValue(String[] args, int index, String name) {
        if (index >= args.length) {
            throw new IllegalArgumentException("option " + name + " requires a value");
        }
        return args[index];
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid port: " + value, e);
        }
    }
}
