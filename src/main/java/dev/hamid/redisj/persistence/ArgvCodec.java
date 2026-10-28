package dev.hamid.redisj.persistence;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Encodes a command as a RESP array of bulk strings, the format used by the append-only file and
 * the snapshot file.
 */
public final class ArgvCodec {

    private ArgvCodec() {
    }

    /**
     * @param argv the command name and arguments
     * @return the RESP encoding
     */
    public static byte[] encode(List<byte[]> argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii("*" + argv.size() + "\r\n", out);
        for (byte[] arg : argv) {
            writeAscii("$" + arg.length + "\r\n", out);
            out.writeBytes(arg);
            writeAscii("\r\n", out);
        }
        return out.toByteArray();
    }

    /**
     * @param parts the command name and arguments as text (UTF-8)
     * @return the RESP encoding
     */
    public static byte[] encodeText(String... parts) {
        List<byte[]> argv = new java.util.ArrayList<>(parts.length);
        for (String part : parts) {
            argv.add(part.getBytes(StandardCharsets.UTF_8));
        }
        return encode(argv);
    }

    private static void writeAscii(String text, ByteArrayOutputStream out) {
        out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    }
}
