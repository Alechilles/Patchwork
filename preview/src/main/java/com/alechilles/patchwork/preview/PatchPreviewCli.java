package com.alechilles.patchwork.preview;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Command-line entry point: {@code java -jar patchwork-preview.jar [options]}. */
public final class PatchPreviewCli {
    private static final String USAGE = """
            Usage: java -jar patchwork-preview.jar [options]
              --assets <Assets.zip|dir>   Base game assets, loaded first as Hytale:Hytale.
              --pack <dir|zip>            Asset pack root with manifest.json; repeat in load order.
              --installed <id[=version]>  Extra installed plugin ID, for example Alechilles:Alec's Tamework!=5.4.0.
              --server-version <version>  Hytale server version for ServerVersion conditions.
              --server-jar <HytaleServer.jar>  Read the server version from this jar instead.
              --mod-data <id>=<dir>       Data directory for ModData condition sources.
              --target <asset path>       Report only this target; repeatable.
              --no-documents              Leave out the final documents.
              --out <file>                Write the JSON report here instead of standard output.
            Without --server-version or --server-jar, the version is read from Server/HytaleServer.jar
            next to --assets.
            """;

    private PatchPreviewCli() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args, System.out));
        } catch (IllegalArgumentException | IOException failure) {
            System.err.println("patchwork-preview: " + failure.getMessage());
            System.err.print(USAGE);
            System.exit(2);
        }
    }

    static int run(String[] args, PrintStream out) throws IOException {
        Path assets = null;
        List<Path> packs = new ArrayList<>();
        Map<String, String> installed = new LinkedHashMap<>();
        Map<String, Path> modData = new LinkedHashMap<>();
        Set<String> targets = new LinkedHashSet<>();
        String serverVersion = null;
        Path serverJar = null;
        Path output = null;
        boolean documents = true;
        for (int index = 0; index < args.length; index++) {
            String option = args[index];
            switch (option) {
                case "--help", "-h" -> {
                    out.print(USAGE);
                    return 0;
                }
                case "--no-documents" -> documents = false;
                case "--assets" -> assets = Path.of(value(args, ++index, option));
                case "--pack" -> packs.add(Path.of(value(args, ++index, option)));
                case "--installed" -> {
                    String[] parts = value(args, ++index, option).split("=", 2);
                    installed.put(parts[0], parts.length > 1 ? parts[1] : "");
                }
                case "--server-version" -> serverVersion = value(args, ++index, option);
                case "--server-jar" -> serverJar = Path.of(value(args, ++index, option));
                case "--mod-data" -> {
                    String[] parts = value(args, ++index, option).split("=", 2);
                    if (parts.length != 2) throw new IllegalArgumentException("--mod-data needs <id>=<dir>.");
                    modData.put(parts[0], Path.of(parts[1]));
                }
                case "--target" -> targets.add(value(args, ++index, option));
                case "--out" -> output = Path.of(value(args, ++index, option));
                default -> throw new IllegalArgumentException("Unknown option: " + option);
            }
        }
        if (assets == null && packs.isEmpty()) throw new IllegalArgumentException("Give --assets or at least one --pack.");
        if (serverVersion == null) {
            if (serverJar == null && assets != null) serverJar = assets.toAbsolutePath().getParent().resolve("Server/HytaleServer.jar");
            serverVersion = serverJar == null ? null : jarVersion(serverJar);
            if (serverVersion == null) throw new IllegalArgumentException("Give --server-version (no readable HytaleServer.jar version).");
        }

        List<PatchPreview.Pack> inputs = new ArrayList<>();
        if (assets != null) inputs.add(new PatchPreview.Pack(PatchPreview.BASE_PACK_ID, serverVersion, assets.toAbsolutePath()));
        for (Path pack : packs) inputs.add(packFromManifest(pack.toAbsolutePath()));

        JsonObject report = new PatchPreview().run(new PatchPreview.Request(
                inputs, installed, serverVersion, modData, targets, documents));
        String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create().toJson(report);
        if (output == null) {
            out.println(json);
        } else {
            Files.writeString(output, json + System.lineSeparator(), StandardCharsets.UTF_8);
        }
        return 0;
    }

    /** Builds the pack ID as Hytale does ({@code PluginIdentifier}: {@code Group:Name}). */
    static PatchPreview.Pack packFromManifest(Path pack) throws IOException {
        JsonObject manifest;
        if (Files.isDirectory(pack)) {
            Path file = pack.resolve("manifest.json");
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("No manifest.json in pack " + pack);
            manifest = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } else if (Files.isRegularFile(pack)) {
            try (ZipFile zip = new ZipFile(pack.toFile())) {
                ZipEntry entry = zip.getEntry("manifest.json");
                if (entry == null) throw new IllegalArgumentException("No manifest.json in pack " + pack);
                try (InputStream input = zip.getInputStream(entry)) {
                    manifest = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                }
            }
        } else {
            throw new IllegalArgumentException("Pack not found: " + pack);
        }
        String group = text(manifest, "Group");
        String name = text(manifest, "Name");
        if (group == null || name == null) throw new IllegalArgumentException("manifest.json needs Group and Name: " + pack);
        return new PatchPreview.Pack(group + ":" + name, text(manifest, "Version"), pack);
    }

    private static String jarVersion(Path jar) {
        if (!Files.isRegularFile(jar)) return null;
        try (JarFile file = new JarFile(jar.toFile())) {
            var manifest = file.getManifest();
            return manifest == null ? null : manifest.getMainAttributes().getValue("Implementation-Version");
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static String text(JsonObject object, String key) {
        var value = object.get(key);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) throw new IllegalArgumentException(option + " needs a value.");
        return args[index];
    }
}
