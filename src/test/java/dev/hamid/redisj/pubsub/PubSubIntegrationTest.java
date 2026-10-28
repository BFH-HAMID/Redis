package dev.hamid.redisj.pubsub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hamid.redisj.command.ServerState;
import dev.hamid.redisj.command.CommandRegistry;
import dev.hamid.redisj.server.RedisServer;
import dev.hamid.redisj.server.ServerOptions;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

@Timeout(60)
class PubSubIntegrationTest {

    private RedisServer server;
    private Jedis publisher;
    private Socket rawSocket;
    private OutputStream rawOut;
    private InputStream rawIn;

    @BeforeEach
    void start() throws IOException {
        ServerState state = new ServerState(16, null, System::currentTimeMillis);
        server = new RedisServer(ServerOptions.ephemeral(), CommandRegistry.withDefaults(state));
        state.attachClients(server::clients);
        server.start();
        publisher = new Jedis("127.0.0.1", server.port());
        rawSocket = new Socket("127.0.0.1", server.port());
        rawOut = rawSocket.getOutputStream();
        rawIn = new BufferedInputStream(rawSocket.getInputStream());
    }

    @AfterEach
    void stop() throws IOException {
        rawSocket.close();
        publisher.close();
        server.close();
    }

    /** A Jedis subscriber running its blocking loop on its own thread. */
    private final class Listener {
        final CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        final CountDownLatch subscribed = new CountDownLatch(1);
        final CountDownLatch messages;
        final Jedis jedis = new Jedis("127.0.0.1", server.port());
        final JedisPubSub pubSub;
        final Thread thread;
        final boolean pattern;

        Listener(int expectedMessages, boolean pattern, String target) {
            this.pattern = pattern;
            messages = new CountDownLatch(expectedMessages);
            pubSub = new JedisPubSub() {
                @Override
                public void onSubscribe(String channel, int count) {
                    subscribed.countDown();
                }

                @Override
                public void onPSubscribe(String pat, int count) {
                    subscribed.countDown();
                }

                @Override
                public void onMessage(String channel, String message) {
                    received.add(channel + "=" + message);
                    messages.countDown();
                }

                @Override
                public void onPMessage(String p, String channel, String message) {
                    received.add(p + "|" + channel + "=" + message);
                    messages.countDown();
                }
            };
            thread = new Thread(() -> {
                if (pattern) {
                    jedis.psubscribe(pubSub, target);
                } else {
                    jedis.subscribe(pubSub, target);
                }
            });
            thread.start();
        }

        void awaitSubscribed() throws InterruptedException {
            assertTrue(subscribed.await(5, TimeUnit.SECONDS), "subscribe confirmation");
        }

        void close() throws InterruptedException {
            if (pattern) {
                pubSub.punsubscribe();
            } else {
                pubSub.unsubscribe();
            }
            thread.join(5000);
            jedis.close();
        }
    }

    @Test
    void channelSubscriberReceivesPublishedMessage() throws Exception {
        Listener listener = new Listener(1, false, "news");
        listener.awaitSubscribed();
        assertEquals(1L, publisher.publish("news", "hello"));
        assertTrue(listener.messages.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("news=hello"), listener.received);
        listener.close();
    }

    @Test
    void patternSubscriberReceivesMatchingChannels() throws Exception {
        Listener listener = new Listener(1, true, "news.*");
        listener.awaitSubscribed();
        assertEquals(0L, publisher.publish("sport", "ignored"));
        assertEquals(1L, publisher.publish("news.sport", "goal"));
        assertTrue(listener.messages.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("news.*|news.sport=goal"), listener.received);
        listener.close();
    }

    @Test
    void subscribedConnectionRejectsOtherCommandsButAllowsPing() throws IOException {
        send("SUBSCRIBE", "news");
        assertEquals(List.of("*3", "$9", "subscribe", "$4", "news", ":1"), readLines(6));
        send("GET", "k");
        assertEquals("-ERR Can't execute 'get': only (P|S)SUBSCRIBE / (P|S)UNSUBSCRIBE / PING / QUIT"
                + " / RESET are allowed in this context", readLine());
        send("PING");
        assertEquals(List.of("*2", "$4", "pong", "$0", ""), readLines(5));
        send("PING", "hi");
        assertEquals(List.of("*2", "$4", "pong", "$2", "hi"), readLines(5));
    }

    @Test
    void disconnectedSubscriberIsForgotten() throws Exception {
        send("SUBSCRIBE", "gone");
        readLines(6);
        rawSocket.close();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        long receivers = 1;
        while (receivers != 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
            receivers = publisher.publish("gone", "x");
        }
        assertEquals(0L, receivers);
    }

    private void send(String... args) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length).append("\r\n");
        for (String arg : args) {
            sb.append('$').append(arg.getBytes(StandardCharsets.UTF_8).length).append("\r\n")
                    .append(arg).append("\r\n");
        }
        rawOut.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        rawOut.flush();
    }

    private List<String> readLines(int count) throws IOException {
        List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(readLine());
        }
        return lines;
    }

    private String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = rawIn.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                line.append((char) c);
            }
        }
        return line.toString();
    }
}
