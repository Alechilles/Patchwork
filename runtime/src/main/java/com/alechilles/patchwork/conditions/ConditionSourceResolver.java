package com.alechilles.patchwork.conditions;

import com.alechilles.patchwork.discovery.PatchSource;
import com.alechilles.patchwork.discovery.PatchScanner;
import com.alechilles.patchwork.discovery.PatchTargetResolver;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Resolves one condition source to a cached JSON snapshot without exposing JSON values in diagnostics. */
public final class ConditionSourceResolver {
    private final PatchTargetResolver targetResolver;
    private final ModDataRootRegistry modDataRoots;
    private final ConditionDocumentCache cache;
    private final AtomicBoolean claimed = new AtomicBoolean();
    /** Creates a resolver for exactly one generation pass. */
    public ConditionSourceResolver(PatchTargetResolver targetResolver, ModDataRootRegistry modDataRoots, ConditionDocumentCache cache) { this.targetResolver = Objects.requireNonNull(targetResolver); this.modDataRoots = Objects.requireNonNull(modDataRoots); this.cache = Objects.requireNonNull(cache); }
    /** Resolves target bytes, game assets, or ModData with first-snapshot cache semantics. */
    public Result resolve(ConditionSource source, String targetPath, byte[] targetBytes, List<PatchSource> sources) {
        final ConditionDocumentCache.SourceKey key;
        try { key = key(source, targetPath); }
        catch (IllegalArgumentException invalid) { return new Result(ResultStatus.FAILED, null, "Unsafe condition source path."); }
        ConditionDocumentCache.Snapshot snapshot = cache.getOrResolve(key, () -> read(source, targetPath, targetBytes, sources));
        return new Result(ResultStatus.valueOf(snapshot.status().name()), snapshot.document(), snapshot.diagnostic());
    }
    /** Returns this pass's document cache for lifecycle inspection. */
    public ConditionDocumentCache documentCache() { return cache; }
    /** Claims this resolver for one complete generation pass. Callers must create a fresh resolver/cache for each startup or reload. */
    public void claimGenerationPass() {
        if (!claimed.compareAndSet(false, true)) throw new IllegalStateException("ConditionSourceResolver is single-use; create a fresh resolver/cache for each generation pass.");
    }
    /** Checks an asset without parsing it as JSON. */
    public PatchTargetResolver.Resolution assetResolution(List<PatchSource> sources, String path) { return targetResolver.resolveDetailed(sources, path); }
    private ConditionDocumentCache.Snapshot read(ConditionSource source, String target, byte[] bytes, List<PatchSource> sources) {
        if (source instanceof ConditionSource.Target) return parse(bytes, "Target document is missing.", "Target JSON is malformed.");
        if (source instanceof ConditionSource.Asset asset) {
            var found = targetResolver.resolveDetailed(sources, asset.path());
            return switch (found.status()) { case FOUND -> parse(found.resolvedTarget().bytes(), "", "Asset JSON is malformed: " + asset.path()); case MISSING -> missing("Asset source is missing: " + asset.path()); case FAILED -> failed(found.diagnostic()); };
        }
        ConditionSource.ModData mod = (ConditionSource.ModData) source;
        ModDataRootRegistry.ReadResult result = modDataRoots.readJson(mod.modId(), mod.path());
        return switch (result.status()) { case MISSING -> missing(result.diagnostic()); case FAILED -> failed(result.diagnostic()); case FOUND -> parse(result.bytes(), "", "ModData JSON is malformed: " + mod.modId() + "/" + mod.path()); };
    }
    private static ConditionDocumentCache.Snapshot parse(byte[] bytes, String absent, String malformed) { if (bytes == null) return missing(absent); try { JsonElement element = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)); return new ConditionDocumentCache.Snapshot(ConditionDocumentCache.Status.FOUND, element, ""); } catch (Exception e) { return failed(malformed); } }
    private static ConditionDocumentCache.Snapshot missing(String diagnostic) { return new ConditionDocumentCache.Snapshot(ConditionDocumentCache.Status.MISSING, null, diagnostic); }
    private static ConditionDocumentCache.Snapshot failed(String diagnostic) { return new ConditionDocumentCache.Snapshot(ConditionDocumentCache.Status.FAILED, null, diagnostic); }
    private ConditionDocumentCache.SourceKey key(ConditionSource source, String target) { if (source instanceof ConditionSource.Target) return new ConditionDocumentCache.SourceKey("Target", PatchScanner.normalizeAssetPath(target), ""); if (source instanceof ConditionSource.Asset asset) return new ConditionDocumentCache.SourceKey("Asset", PatchScanner.normalizeAssetPath(asset.path()), ""); ConditionSource.ModData mod = (ConditionSource.ModData) source; return new ConditionDocumentCache.SourceKey("ModData", mod.modId(), modDataRoots.validateRelativePath(mod.path())); }
    /** Source result with status and a defensive document snapshot. */
    public record Result(ResultStatus status, JsonElement document, String diagnostic) { public Result { document = document == null ? null : document.deepCopy(); diagnostic = diagnostic == null ? "" : diagnostic; } @Override public JsonElement document() { return document == null ? null : document.deepCopy(); } }
    /** Resolution status. */
    public enum ResultStatus { FOUND, MISSING, FAILED }
}
