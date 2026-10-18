package com.alechilles.patchwork.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards what asset tooling reads from the preview: outcomes, final documents and conflict values. */
final class PatchPreviewTest {
    private static final String TARGET = "Server/Item/Items/Thing.json";

    @TempDir Path temporary;

    @Test
    void reportsGatedOverlayConflictValuesAndFinalDocument() throws IOException {
        Path base = write("base", TARGET, "{\"Capacity\":8,\"Name\":\"Thing\"}");
        Path first = write("first", "Server/Patchwork/Patches/Wider.json",
                "{\"Id\":\"Wider\",\"Target\":\"" + TARGET + "\",\"Operations\":["
                        + "{\"Op\":\"Replace\",\"Path\":\"/Capacity\",\"Value\":16}]}");
        Path second = write("second", "Server/Patchwork/Patches/Widest.json",
                "{\"Id\":\"Widest\",\"Target\":\"" + TARGET + "\",\"Priority\":5,\"Operations\":["
                        + "{\"Op\":\"Replace\",\"Path\":\"/Capacity\",\"Value\":32}]}");
        write("second", "Server/Patchwork/Patches/Gated.json",
                "{\"Id\":\"Gated\",\"Target\":\"" + TARGET + "\",\"When\":{\"ModInstalled\":\"Example:Absent\"},"
                        + "\"Operations\":[{\"Op\":\"Remove\",\"Path\":\"/Name\"}]}");

        JsonObject report = new PatchPreview().run(new PatchPreview.Request(List.of(
                new PatchPreview.Pack(PatchPreview.BASE_PACK_ID, "0.7.2", base),
                new PatchPreview.Pack("Example:First", "1.0", first),
                new PatchPreview.Pack("Example:Second", "1.0", second)),
                Map.of(), "0.7.2", Map.of(), Set.of(), true));

        assertEquals("applied", overlay(report, "Wider").get("outcome").getAsString());
        assertEquals("applied", overlay(report, "Widest").get("outcome").getAsString());
        JsonObject gated = overlay(report, "Gated");
        assertEquals("gated", gated.get("outcome").getAsString());
        assertEquals("Required mod is not installed: Example:Absent", gated.get("reason").getAsString());

        JsonObject target = report.getAsJsonArray("targets").get(0).getAsJsonObject();
        assertEquals("patched", target.get("status").getAsString());
        assertEquals(32, target.getAsJsonObject("document").get("Capacity").getAsInt());
        assertEquals("Thing", target.getAsJsonObject("document").get("Name").getAsString());

        JsonArray conflicts = report.getAsJsonArray("conflicts");
        assertEquals(1, conflicts.size());
        JsonObject conflict = conflicts.get(0).getAsJsonObject();
        assertEquals("/Capacity", conflict.get("path").getAsString());
        assertEquals("CROSS_PACK", conflict.get("scope").getAsString());
        assertEquals(16, conflict.getAsJsonObject("earlier").get("value").getAsInt());
        assertEquals(32, conflict.getAsJsonObject("later").get("value").getAsInt());
        assertEquals(32, conflict.get("final").getAsInt());
    }

    private static JsonObject overlay(JsonObject report, String patchId) {
        for (JsonElement row : report.getAsJsonArray("overlays")) {
            if (row.getAsJsonObject().get("patchId").getAsString().equals(patchId)) return row.getAsJsonObject();
        }
        throw new AssertionError("No overlay row for " + patchId);
    }

    private Path write(String pack, String path, String json) throws IOException {
        Path root = temporary.resolve(pack);
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
        return root;
    }
}
