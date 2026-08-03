package com.alechilles.patchwork.generation;

import com.alechilles.patchwork.discovery.PatchScanner;
import com.alechilles.patchwork.format.Utf8Ordering;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** Deterministic manifest for the complete generated pack publication. */
public record GeneratedPackManifest(List<Entry> entries) {
    /** File written before a staged directory can become active. */
    public static final String FILE_NAME = "patchwork-manifest.json";
    public GeneratedPackManifest { entries = List.copyOf(entries.stream().sorted(Comparator.comparing(Entry::target, Utf8Ordering.UNSIGNED_BYTES)).map(entry -> new Entry(entry.target(), entry.bytes())).toList()); }
    /** Serializes stable target, length, and SHA-256 metadata without source content. */
    public byte[] bytes() {
        JsonArray files = new JsonArray();
        for (Entry entry : entries) { JsonObject file = new JsonObject(); file.addProperty("Target", entry.target()); file.addProperty("Length", entry.bytes().length); file.addProperty("Sha256", sha256(entry.bytes())); files.add(file); }
        JsonObject root = new JsonObject(); root.add("Files", files); return root.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static String sha256(byte[] bytes) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception error) { throw new IllegalStateException("SHA-256 is unavailable", error); } }
    /** One immutable generated target payload. */
    public record Entry(String target, byte[] bytes) {
        public Entry { target = PatchScanner.normalizeAssetPath(target); bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
}
