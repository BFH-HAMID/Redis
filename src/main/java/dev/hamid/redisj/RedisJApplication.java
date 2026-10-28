package dev.hamid.redisj;

import dev.hamid.redisj.config.CommandLineArgs;
import dev.hamid.redisj.config.ConfigException;
import dev.hamid.redisj.config.ServerConfig;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line entry point for RedisJ.
 *
 * <p>In this phase the application parses options and configuration, prints a banner
 * and a configuration summary, and exits. The network listener is added in a later phase.
 */
public final class RedisJApplication {

    /** Project version reported in the banner. */
    public static final String VERSION = "0.1.0-SNAPSHOT";

    /** Usage text printed for {@code --help}. */
    static final String USAGE = """
            Usage: java -jar redisj.jar [options] [configfile]
              --port <port>      TCP port to listen on (default 6379)
              --config <file>    Path to a redisj.conf file
              -h, --help         Show this help and exit
            """;

    private RedisJApplication() {
    }

    /**
     * Starts RedisJ from the command line.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs the application logic with the given streams.
     *
     * @param args the command-line arguments
     * @param out  destination for normal output
     * @param err  destination for error messages
     * @return the process exit code: {@code 0} on success, {@code 1} on a usage or config error
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        ServerConfig config;
        try {
            CommandLineArgs.Options options = CommandLineArgs.parse(args);
            if (options.help()) {
                out.print(USAGE);
                return 0;
            }
            config = loadConfig(options);
        } catch (IOException | ConfigException | IllegalArgumentException e) {
            err.println("redisj: " + e.getMessage());
            return 1;
        }
        out.println(banner());
        out.println("Configuration: " + config);
        out.println("Network listener is not started yet (the NIO event loop is added in Phase 3).");
        return 0;
    }

    /**
     * Builds the effective configuration: file first, then command-line overrides.
     *
     * @param options parsed command-line options
     * @return the effective configuration
     * @throws IOException     if the configuration file cannot be read
     * @throws ConfigException if the configuration file is invalid or the file is missing
     */
    static ServerConfig loadConfig(CommandLineArgs.Options options) throws IOException {
        ServerConfig base = ServerConfig.defaults();
        Path file = options.configFile();
        if (file != null) {
            if (!Files.isRegularFile(file)) {
                throw new ConfigException("cannot read config file: " + file);
            }
            base = ServerConfig.load(file);
        }
        ServerConfig.Builder builder = base.toBuilder();
        if (options.port() != null) {
            builder.port(options.port());
        }
        return builder.build();
    }

    /**
     * @return the startup banner
     */
    static String banner() {
        return String.join(System.lineSeparator(),
                "RedisJ " + VERSION,
                "RESP-compatible in-memory data store (Java " + Runtime.version().feature() + ")");
    }
}
