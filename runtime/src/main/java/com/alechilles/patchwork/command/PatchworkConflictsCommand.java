package com.alechilles.patchwork.command;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Console-compatible conflict report command with an optional exact target variant. */
public final class PatchworkConflictsCommand extends AbstractAsyncCommand {
    private final PatchworkCommandActions actions;

    public PatchworkConflictsCommand(PatchworkCommandActions actions) {
        super("conflicts", "Show overlapping Patchwork effects");
        this.actions = Objects.requireNonNull(actions, "actions");
        requirePermission(PatchworkCommandRoot.ADMIN_PERMISSION);
        setPermissionGroups(PatchworkCommandRoot.DEFAULT_GROUP);
        addUsageVariant(new TargetVariant(actions));
    }

    @Override protected CompletableFuture<Void> executeAsync(CommandContext context) {
        return actions.conflicts(null).thenAccept(lines -> send(context, lines)).toCompletableFuture();
    }

    private static void send(CommandContext context, List<String> lines) {
        for (String line : lines) context.sendMessage(Message.raw(line));
    }

    private static final class TargetVariant extends AbstractAsyncCommand {
        private final RequiredArg<String> target = withRequiredArg(
                "target", "Exact generated target path", ArgTypes.STRING);
        private final PatchworkCommandActions actions;

        private TargetVariant(PatchworkCommandActions actions) {
            super("Show conflicts for one exact target");
            this.actions = actions;
            requirePermission(PatchworkCommandRoot.ADMIN_PERMISSION);
            setPermissionGroups(PatchworkCommandRoot.DEFAULT_GROUP);
        }

        @Override protected CompletableFuture<Void> executeAsync(CommandContext context) {
            return actions.conflicts(target.get(context))
                    .thenAccept(lines -> send(context, lines)).toCompletableFuture();
        }
    }
}
