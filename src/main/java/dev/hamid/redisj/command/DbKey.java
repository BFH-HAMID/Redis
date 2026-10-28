package dev.hamid.redisj.command;

/**
 * A key qualified by its database index. Watches are scoped per database.
 *
 * @param db  the database index
 * @param key the key name (ISO-8859-1)
 */
public record DbKey(int db, String key) {
}
