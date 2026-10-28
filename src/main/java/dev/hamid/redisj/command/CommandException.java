package dev.hamid.redisj.command;

/**
 * An error that a command reports to the client as a RESP error reply.
 *
 * <p>The message is the complete error line, including its code, such as
 * {@code "ERR syntax error"} or {@code "WRONGTYPE ..."}. The stack trace is disabled, because these
 * are expected control-flow exceptions.
 */
public final class CommandException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message the complete error text, starting with an error code
     */
    public CommandException(String message) {
        super(message, null, false, false);
    }

    /**
     * @return the generic syntax error
     */
    public static CommandException syntax() {
        return new CommandException("ERR syntax error");
    }

    /**
     * @return the error for an operation against a key holding the wrong kind of value
     */
    public static CommandException wrongType() {
        return new CommandException("WRONGTYPE Operation against a key holding the wrong kind of value");
    }

    /**
     * @return the error for a value that is not an integer or is out of range
     */
    public static CommandException notInteger() {
        return new CommandException("ERR value is not an integer or out of range");
    }

    /**
     * @return the error for a value that is not a valid floating point number
     */
    public static CommandException notFloat() {
        return new CommandException("ERR value is not a valid float");
    }

    /**
     * @return the error for a command that references a key that does not exist
     */
    public static CommandException noSuchKey() {
        return new CommandException("ERR no such key");
    }
}
