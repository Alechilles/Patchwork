package com.alechilles.patchwork.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** Tests host-provided patch macro registration and lookup behavior. */
final class PatchMacroRegistryTest {

    @Test
    void expandsMacroIdsCaseInsensitivelyWhileRetainingDeclaredIdInErrors() {
        PatchMacroRegistry registry = new PatchMacroRegistry();
        PatchOperation operation = PatchOperation.raw("macro-op", "Macro", null, null, true, null, null, null);
        registry.register("host-one", "ExampleMacro", ignored -> List.of(PatchOperation.raw(
                "expanded", "Add", "/flag", null, true, new com.google.gson.JsonPrimitive(true), null, null
        )));

        assertEquals("expanded", registry.expand(operation.withMacro("examplemacro")).getFirst().id());
        IllegalArgumentException unknown = assertThrows(
                IllegalArgumentException.class,
                () -> registry.expand(operation.withMacro("MissingMacro"))
        );
        assertEquals("Unsupported macro 'MissingMacro'.", unknown.getMessage());
    }

    @Test
    void rejectsDuplicateMacroIdsAndNamesBothHosts() {
        PatchMacroRegistry registry = new PatchMacroRegistry();
        registry.register("host-one", "ExampleMacro", operation -> List.of(operation));

        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> registry.register("host-two", "examplemacro", operation -> List.of(operation))
        );
        assertEquals("Macro ID 'examplemacro' is already registered by host 'host-one'; host 'host-two' cannot register it.", duplicate.getMessage());
    }
}
