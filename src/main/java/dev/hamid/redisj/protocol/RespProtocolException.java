package dev.hamid.redisj.protocol;

/**
 * Signals malformed or over-limit input on the wire.
 *
 * <p>After this exception the parser state is undefined. The connection must reply with
 * an error and then be closed, as Redis does.
 */
public final class RespProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message description, normally starting with {@code "Protocol error: "}
     */
    public RespProtocolException(String message) {
        super(message);
    }
}
