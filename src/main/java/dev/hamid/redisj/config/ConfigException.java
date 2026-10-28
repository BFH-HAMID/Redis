package dev.hamid.redisj.config;

/**
 * Signals an invalid configuration value or a malformed {@code redisj.conf} file.
 */
public final class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a configuration exception.
     *
     * @param message human-readable description of the problem
     */
    public ConfigException(String message) {
        super(message);
    }
}
