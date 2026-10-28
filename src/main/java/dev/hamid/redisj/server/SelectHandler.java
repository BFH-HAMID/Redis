package dev.hamid.redisj.server;

import java.nio.channels.SelectionKey;

/**
 * Receives readiness notifications for a channel registered with an {@link EventLoop}.
 * The handler is stored as the key attachment.
 */
@FunctionalInterface
interface SelectHandler {

    /**
     * Called on the loop thread when the key is ready.
     *
     * @param key the ready selection key
     */
    void onReady(SelectionKey key);
}
