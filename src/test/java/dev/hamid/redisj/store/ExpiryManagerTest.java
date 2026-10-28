package dev.hamid.redisj.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ExpiryManagerTest {

    @Test
    void cycleRemovesExpiredKeysAcrossDatabases() {
        AtomicLong now = new AtomicLong(1_000);
        Databases databases = new Databases(3, now::get);
        for (int d = 0; d < 3; d++) {
            Database db = databases.get(d);
            for (int i = 0; i < 100; i++) {
                db.put("k" + i, "v".getBytes(StandardCharsets.UTF_8), false);
                db.setExpireAt("k" + i, 1_500);
            }
        }
        now.set(2_000);

        ExpiryManager manager = new ExpiryManager(databases, new Random(42));
        long removed = manager.runActiveCycle();

        assertEquals(300, removed);
        for (int d = 0; d < 3; d++) {
            assertEquals(0, databases.get(d).size());
        }
    }

    @Test
    void cycleLeavesKeysWithoutTtlAlone() {
        AtomicLong now = new AtomicLong(1_000);
        Databases databases = new Databases(1, now::get);
        Database db = databases.get(0);
        db.put("forever", "v".getBytes(StandardCharsets.UTF_8), false);
        db.put("short", "v".getBytes(StandardCharsets.UTF_8), false);
        db.setExpireAt("short", 1_100);
        now.set(5_000);

        new ExpiryManager(databases, new Random(7)).runActiveCycle();

        assertNotNull(db.get("forever"));
        assertEquals(1, db.size());
        assertTrue(db.expiredKeys() >= 1);
    }
}
