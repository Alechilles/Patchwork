package com.alechilles.patchwork.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Registry of patch macros contributed by hosting plugins. */
public final class PatchMacroRegistry {
    private Map<String, Registration> registrations = new LinkedHashMap<>();

    /** Registers one macro expander for a host plugin. */
    public synchronized void register(String hostPluginIdentifier, String macroId, MacroExpander expander) {
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
    public synchronized List<PatchOperation> expand(PatchOperation operation) {
        if (!"macro".equalsIgnoreCase(operation.op())) return List.of(operation);
        String macroId = operation.macro();
        if (macroId == null || macroId.isBlank()) throw new IllegalArgumentException("Macro operation " + operation.id() + " requires Macro.");
        Registration registration = registrations.get(normalize(macroId));
        if (registration == null) throw new IllegalArgumentException("Unsupported macro '" + macroId + "'.");
        return List.copyOf(registration.expander().expand(operation));
    }
    /** Removes the exact macro ID when the supplied host owns it. */
    public synchronized boolean unregister(String hostPluginIdentifier, String macroId) {
        Registration registration = registrations.get(normalize(macroId));
        if (registration == null || !registration.hostPluginIdentifier().equals(hostPluginIdentifier)) return false;
        registrations.remove(normalize(macroId));
        return true;
    }
    /** Removes every registered macro before an elected host applies a new immutable snapshot. */
    public synchronized void clear() { registrations.clear(); }

    /** Atomically replaces every macro with a fully validated snapshot. */
    public synchronized void replace(List<MacroRegistration> snapshot) {
        Map<String, Registration> proposed = new LinkedHashMap<>();
        for (MacroRegistration entry : snapshot) {
            Objects.requireNonNull(entry);
            String key = normalize(entry.macroId());
            if (entry.hostPluginIdentifier().isBlank() || entry.macroId().isBlank() || entry.expander() == null || proposed.putIfAbsent(key, new Registration(entry.hostPluginIdentifier(), entry.expander())) != null) {
                throw new IllegalArgumentException("Invalid or duplicate macro snapshot entry: " + entry.macroId());
            }
        }
        registrations = proposed;
    }

    private static String normalize(String macroId) { return macroId.toLowerCase(Locale.ROOT); }

    /** Functional contract for a host-provided macro expansion. */
    @FunctionalInterface
    public interface MacroExpander {
        List<PatchOperation> expand(PatchOperation operation);
    }

    /** One immutable registration used when replacing an elected runtime snapshot. */
    public record MacroRegistration(String hostPluginIdentifier, String macroId, MacroExpander expander) { }

    private record Registration(String hostPluginIdentifier, MacroExpander expander) { }
}
