package dev.hamid.redisj.server;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A blocking raw-socket client for protocol tests. It sends bytes exactly as given and reads them back.
 */
final class RawClient implements AutoCloseable {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    RawClient(int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        socket.setSoTimeout(10_000);
        socket.setTcpNoDelay(true);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    void send(String text) throws IOException {
        sendBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    void sendBytes(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    /** Reads exactly {@code length} bytes and returns them as a string. */
    String readText(int length) throws IOException {
        return new String(readBytes(length), StandardCharsets.UTF_8);
    }

    byte[] readBytes(int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(buffer, offset, length - offset);
            if (n < 0) {
                throw new EOFException("connection closed after " + offset + " of " + length + " bytes");
            }
            offset += n;
        }
        return buffer;
    }

    /** Returns true if the server closes the connection before sending any more bytes. */
    boolean isClosedByPeer() throws IOException {
        return in.read() == -1;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
