package com.alechilles.patchwork.command;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
/** Console-compatible reload subcommand; composition supplies elected work admission. */
public final class PatchworkReloadCommand extends AbstractAsyncCommand {
    private final PatchworkCommandActions actions;

    /** Creates the reload command with elected-host actions. */
    public PatchworkReloadCommand(PatchworkCommandActions actions) {
        super("reload", "Reload Patchwork output");
        this.actions = Objects.requireNonNull(actions, "actions");
        requirePermission(PatchworkCommandRoot.ADMIN_PERMISSION);
        setPermissionGroups(PatchworkCommandRoot.DEFAULT_GROUP);
    }

    @Override protected CompletableFuture<Void> executeAsync(CommandContext context) {
        return actions.reload().thenAccept(lines -> send(context, lines)).toCompletableFuture();
    }

    private static void send(CommandContext context, List<String> lines) {
        for (String line : lines) context.sendMessage(com.hypixel.hytale.server.core.Message.raw(line));
    }
}
