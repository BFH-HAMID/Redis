package dev.hamid.redisj.store;

/**
 * What happens to writes when the dataset exceeds {@code maxmemory}.
 */
public enum EvictionPolicy {

    /** Reject writes that need memory. This is the default. */
    NOEVICTION("noeviction"),

    /** Remove random keys, from any database, until the dataset fits. */
    ALLKEYS_RANDOM("allkeys-random");

    private final String directive;

    EvictionPolicy(String directive) {
        this.directive = directive;
    }

    /**
     * @return the name used in configuration files
     */
    public String directive() {
        return directive;
    }

    /**
     * @param text a configuration value, compared case-insensitively
     * @return the matching policy
     * @throws IllegalArgumentException if the value is not a known policy
     */
    public static EvictionPolicy parse(String text) {
        for (EvictionPolicy policy : values()) {
            if (policy.directive.equalsIgnoreCase(text)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("maxmemory-policy must be noeviction or allkeys-random: "
                + text);
    }
}
