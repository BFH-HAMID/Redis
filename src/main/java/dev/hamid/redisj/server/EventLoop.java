package dev.hamid.redisj.server;

import java.io.IOException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A single-threaded {@link Selector} loop.
 *
 * <p>The loop thread runs {@link Selector#select(long)}, then drains cross-thread tasks, then
 * dispatches ready keys to their {@link SelectHandler}. A periodic tick callback runs roughly every
 * 100 ms on the loop thread. Other threads hand work to the loop with {@link #execute(Runnable)},
 * which enqueues the task and wakes the selector.
 *
 * <p>Channels must be registered and used only from the loop thread. Use {@link #execute} to
 * move work onto it.
 */
public final class EventLoop {

    private static final System.Logger LOG = System.getLogger("redisj.server");
    private static final long TICK_MILLIS = 100;
    private static final long TICK_NANOS = TICK_MILLIS * 1_000_000L;

    private final String name;
    private final Selector selector;
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final Runnable tick;
    private volatile boolean running;
    private volatile Thread thread;

    /**
     * Creates a loop. The selector is opened immediately; the thread starts with {@link #start()}.
     *
     * @param name a thread name, for example {@code "redisj-main"}
     * @param tick callback run about every 100 ms on the loop thread, or {@code null}
     * @throws IOException if the selector cannot be opened
     */
    public EventLoop(String name, Runnable tick) throws IOException {
        this.name = Objects.requireNonNull(name, "name");
        this.selector = Selector.open();
        this.tick = tick != null ? tick : () -> {
        };
    }

    /**
     * Starts the loop thread. Calling this twice is an error.
     *
     * @throws IllegalStateException if the loop was already started
     */
    public void start() {
        if (thread != null) {
            throw new IllegalStateException("event loop already started: " + name);
        }
        running = true;
        Thread t = new Thread(this::loop, name);
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /**
     * Requests the loop to stop. It finishes the current iteration, runs remaining tasks,
     * closes its channels and exits. Use {@link #awaitTermination(long)} to wait for that.
     */
    public void shutdown() {
        running = false;
        wakeup();
    }

    /**
     * Schedules a task to run on the loop thread. Safe to call from any thread.
     *
     * @param task the task
     */
    public void execute(Runnable task) {
        tasks.add(Objects.requireNonNull(task, "task"));
        if (!inLoop()) {
            wakeup();
        }
    }

    /**
     * @return {@code true} if the calling thread is this loop's thread
     */
    public boolean inLoop() {
        return Thread.currentThread() == thread;
    }

    /**
     * @return {@code true} while the loop is accepting work
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Waits for the loop thread to exit.
     *
     * @param millis maximum wait in milliseconds; {@code 0} waits indefinitely
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public void awaitTermination(long millis) throws InterruptedException {
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            t.join(millis);
        }
    }

    /**
     * @return the underlying selector; only use from the loop thread
     */
    Selector selector() {
        return selector;
    }

    private void wakeup() {
        try {
            selector.wakeup();
        } catch (ClosedSelectorException ignored) {
            // Loop already closed its selector; nothing left to wake.
        }
    }

    private void loop() {
        long lastTick = System.nanoTime();
        try {
            while (running) {
                selector.select(TICK_MILLIS);
                runTasks();
                processKeys();
                long now = System.nanoTime();
                if (now - lastTick >= TICK_NANOS) {
                    lastTick = now;
                    runTick();
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, name + " stopped after an I/O failure", e);
        } finally {
            running = false;
            runTasks();
            closeAllChannels();
            closeSelector();
        }
    }

    private void processKeys() {
        Set<SelectionKey> keys = selector.selectedKeys();
        for (Iterator<SelectionKey> it = keys.iterator(); it.hasNext(); ) {
            SelectionKey key = it.next();
            it.remove();
            if (!key.isValid()) {
                continue;
            }
            SelectHandler handler = (SelectHandler) key.attachment();
            try {
                handler.onReady(key);
            } catch (java.nio.channels.CancelledKeyException ignored) {
                // The channel was closed while the key was queued; nothing to do.
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "handler failed on " + name, e);
            }
        }
    }

    private void runTasks() {
        Runnable task;
        while ((task = tasks.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "task failed on " + name, e);
            }
        }
    }

    private void runTick() {
        try {
            tick.run();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "tick failed on " + name, e);
        }
    }

    private void closeAllChannels() {
        for (SelectionKey key : new ArrayList<>(selector.keys())) {
            try {
                key.channel().close();
            } catch (IOException ignored) {
                // Best effort during shutdown.
            }
        }
    }

    private void closeSelector() {
        try {
            selector.close();
        } catch (IOException ignored) {
            // Best effort during shutdown.
        }
    }
}
