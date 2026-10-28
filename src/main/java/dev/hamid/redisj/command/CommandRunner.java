package dev.hamid.redisj.command;

import dev.hamid.redisj.protocol.RespValue;

/**
 * Runs a command and records its writes for {@code WATCH}. Every execution path, including the
 * commands run by {@code EXEC}, goes through here so that watches see every modification.
 */
public final class CommandRunner {

    private CommandRunner() {
    }

    /**
     * @param command the command to run
     * @param ctx     the call context
     * @return the command's reply
     * @throws CommandException if the command rejects the call
     */
    public static RespValue run(Command command, CommandContext ctx) {
        RespValue reply = command.execute(ctx);
        if (command.flags().contains(Command.Flag.WRITE)) {
            ctx.server().watches().markWritten(command, ctx);
            ctx.server().writeLog().recordWrite(ctx, command);
        }
        return reply;
    }
}
