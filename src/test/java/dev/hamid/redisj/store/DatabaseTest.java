package dev.hamid.redisj.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DatabaseTest {

    private final AtomicLong now = new AtomicLong(1_000);

    private Database db() {
        return new Databases(1, now::get).get(0);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void lazyExpiryHidesKeyOnlyAfterDeadline() {
        Database db = db();
        db.put("k", bytes("v"), false);
        assertTrue(db.setExpireAt("k", 1_500));

        now.set(1_500);
        assertTrue(db.get("k") != null, "key is still live at its deadline");

        now.set(1_501);
        assertNull(db.get("k"));
        assertEquals(0, db.size(), "expired key is removed on access");
        assertEquals(1, db.expiredKeys());
    }

    @Test
    void plainPutClearsTtlButKeepTtlPreservesIt() {
        Database db = db();
        db.put("k", bytes("v"), false);
        db.setExpireAt("k", 5_000);

        db.put("k", bytes("w"), true);
        assertEquals(5_000, db.get("k").expireAt());

        db.put("k", bytes("x"), false);
        assertFalse(db.get("k").hasExpiry());
        assertEquals(0, db.expiringKeys());
    }

    @Test
    void persistRemovesTtlAndReportsWhetherItDid() {
        Database db = db();
        db.put("k", bytes("v"), false);
        assertFalse(db.persist("k"), "no TTL to remove");
        db.setExpireAt("k", 9_000);
        assertTrue(db.persist("k"));
        assertFalse(db.get("k").hasExpiry());
    }

    @Test
    void renameMovesValueAndTtlAndOverwritesTarget() {
        Database db = db();
        db.put("a", bytes("1"), false);
        db.setExpireAt("a", 4_000);
        db.put("b", bytes("2"), false);

        assertTrue(db.rename("a", "b"));
        assertNull(db.get("a"));
        assertArrayEquals(bytes("1"), (byte[]) db.get("b").value());
        assertEquals(4_000, db.get("b").expireAt());
        assertFalse(db.rename("missing", "x"));
    }

    @Test
    void liveKeysSkipsExpiredKeys() {
        Database db = db();
        db.put("live", bytes("1"), false);
        db.put("dead", bytes("2"), false);
        db.setExpireAt("dead", 1_100);
        now.set(2_000);
        assertEquals(java.util.List.of("live"), db.liveKeys());
    }

    @Test
    void activeCycleRemovesExpiredKeysWithoutReads() {
        Database db = db();
        for (int i = 0; i < 50; i++) {
            db.put("t" + i, bytes("v"), false);
            db.setExpireAt("t" + i, 1_050);
        }
        db.put("forever", bytes("v"), false);
        now.set(2_000);

        // Sampling is random, so a single pass may miss some keys. Repeat until none are left.
        java.util.Random random = new java.util.Random(1);
        int passes = 0;
        while (db.expiringKeys() > 0 && passes++ < 1_000) {
            db.activeExpireCycle(20, random);
        }
        assertEquals(0, db.expiringKeys());
        assertEquals(1, db.size(), "only the key without TTL remains");
        assertEquals(50, db.expiredKeys());
    }
}
