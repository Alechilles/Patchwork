package com.alechilles.patchwork.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Registry of patch macros contributed by hosting plugins. */
public final class PatchMacroRegistry {
    private final Map<String, Registration> registrations = new LinkedHashMap<>();

    /** Registers one macro expander for a host plugin. */
    public void register(String hostPluginIdentifier, String macroId, MacroExpander expander) {
        Objects.requireNonNull(hostPluginIdentifier, "hostPluginIdentifier");
        Objects.requireNonNull(macroId, "macroId");
        Objects.requireNonNull(expander, "expander");
        String key = normalize(macroId);
        Registration existing = registrations.get(key);
        if (existing != null) throw new IllegalArgumentException("Macro ID '" + macroId + "' is already registered by host '"
                + existing.hostPluginIdentifier() + "'; host '" + hostPluginIdentifier + "' cannot register it.");
        registrations.put(key, new Registration(hostPluginIdentifier, expander));
    }

    /** Expands a macro operation, or returns the raw operation unchanged. */
    public List<PatchOperation> expand(PatchOperation operation) {
        if (!"macro".equalsIgnoreCase(operation.op())) return List.of(operation);
        String macroId = operation.macro();
        if (macroId == null || macroId.isBlank()) throw new IllegalArgumentException("Macro operation " + operation.id() + " requires Macro.");
        Registration registration = registrations.get(normalize(macroId));
        if (registration == null) throw new IllegalArgumentException("Unsupported macro '" + macroId + "'.");
        return List.copyOf(registration.expander().expand(operation));
    }

    private static String normalize(String macroId) { return macroId.toLowerCase(Locale.ROOT); }

    /** Functional contract for a host-provided macro expansion. */
    @FunctionalInterface
    public interface MacroExpander {
        List<PatchOperation> expand(PatchOperation operation);
    }

    private record Registration(String hostPluginIdentifier, MacroExpander expander) { }
}
