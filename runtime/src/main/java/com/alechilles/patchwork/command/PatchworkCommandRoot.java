package com.alechilles.patchwork.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/** Elected-only root for Patchwork administration commands. */
public final class PatchworkCommandRoot extends AbstractCommandCollection {
    public static final String ADMIN_PERMISSION = "patchwork.admin";
    public static final String DEFAULT_GROUP = "hytale:Admin";
    public PatchworkCommandRoot(String permission, String defaultGroup, PatchworkCommandActions actions) {
        super("patchwork", "Patchwork administration");
        if (!ADMIN_PERMISSION.equals(permission) || !DEFAULT_GROUP.equals(defaultGroup)) throw new IllegalArgumentException("Patchwork commands require their stable permission contract.");
        requirePermission(permission); setPermissionGroups(defaultGroup);
        addSubCommand(new PatchworkStatusCommand(actions));
        addSubCommand(new PatchworkReloadCommand(actions));
        addSubCommand(new PatchworkSelfTestCommand(actions));
    }
}
