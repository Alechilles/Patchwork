package com.alechilles.patchwork.discovery;

import java.util.Arrays;
import java.util.Objects;

/**
 * One normalized target selector.  Exact paths are the default; wildcard
 * tokens are enabled only by the explicit {@code glob:} prefix.
 */
public record PatchTargetSelector(Kind kind, String expression, String stablePrefix) {
    private static final String GLOB_PREFIX = "glob:";

    public enum Kind { EXACT, GLOB }

    public PatchTargetSelector {
        kind = Objects.requireNonNull(kind, "kind");
        expression = Objects.requireNonNull(expression, "expression");
        stablePrefix = Objects.requireNonNull(stablePrefix, "stablePrefix");
        if (expression.isBlank()) throw new IllegalArgumentException("Target selector must not be blank.");
        if (kind == Kind.GLOB) {
            if (!expression.startsWith(GLOB_PREFIX)) {
                throw new IllegalArgumentException("Glob selectors must begin with glob:.");
            }
            String body = normalizeGlob(expression.substring(GLOB_PREFIX.length()));
            String normalized = GLOB_PREFIX + body;
            if (!normalized.equals(expression)) expression = normalized;
            String expectedPrefix = stablePrefix(body);
            if (!expectedPrefix.equals(stablePrefix)) stablePrefix = expectedPrefix;
        } else {
            if (expression.startsWith(GLOB_PREFIX)) {
                throw new IllegalArgumentException("glob: selectors must use Kind.GLOB.");
            }
            String normalized = normalizeExact(expression);
            if (!normalized.equals(expression)) expression = normalized;
            stablePrefix = "";
        }
    }

    /** Parses one exact or explicitly prefixed glob selector. */
    public static PatchTargetSelector parse(String text) {
        Objects.requireNonNull(text, "text");
        if (text.startsWith(GLOB_PREFIX)) {
            String body = normalizeGlob(text.substring(GLOB_PREFIX.length()));
            return new PatchTargetSelector(Kind.GLOB, GLOB_PREFIX + body, stablePrefix(body));
        }
        return new PatchTargetSelector(Kind.EXACT, normalizeExact(text), "");
    }

    /** Returns whether this selector matches one normalized concrete asset path. */
    public boolean matches(String normalizedAssetPath) {
        if (normalizedAssetPath == null || normalizedAssetPath.isBlank()) return false;
        final String candidate;
        try {
            candidate = PatchScanner.normalizeAssetPath(normalizedAssetPath);
        } catch (IllegalArgumentException unsafe) {
            return false;
        }
        if (kind == Kind.EXACT) return expression.equals(candidate);
        String body = expression.substring(GLOB_PREFIX.length());
        String[] patternSegments = body.split("/", -1);
        String[] candidateSegments = candidate.split("/", -1);
        Boolean[][] memo = new Boolean[patternSegments.length + 1][candidateSegments.length + 1];
        return matchesSegments(patternSegments, candidateSegments, 0, 0, memo);
    }

    /** Convenience view of the selector text including the explicit prefix. */
    public String text() {
        return expression;
    }

    private static boolean matchesSegments(String[] pattern, String[] candidate,
                                           int patternIndex, int candidateIndex,
                                           Boolean[][] memo) {
        Boolean known = memo[patternIndex][candidateIndex];
        if (known != null) return known;
        boolean result;
        if (patternIndex == pattern.length) {
            result = candidateIndex == candidate.length;
        } else if ("**".equals(pattern[patternIndex])) {
            result = matchesSegments(pattern, candidate, patternIndex + 1, candidateIndex, memo)
                    || (candidateIndex < candidate.length
                    && matchesSegments(pattern, candidate, patternIndex, candidateIndex + 1, memo));
        } else {
            result = candidateIndex < candidate.length
                    && matchesSegment(pattern[patternIndex], candidate[candidateIndex])
                    && matchesSegments(pattern, candidate, patternIndex + 1, candidateIndex + 1, memo);
        }
        memo[patternIndex][candidateIndex] = result;
        return result;
    }

    private static boolean matchesSegment(String pattern, String candidate) {
        Boolean[][] memo = new Boolean[pattern.length() + 1][candidate.length() + 1];
        return matchesChars(pattern, candidate, 0, 0, memo);
    }

    private static boolean matchesChars(String pattern, String candidate, int patternIndex,
                                        int candidateIndex, Boolean[][] memo) {
        Boolean known = memo[patternIndex][candidateIndex];
        if (known != null) return known;
        boolean result;
        if (patternIndex == pattern.length()) {
            result = candidateIndex == candidate.length();
        } else {
            char token = pattern.charAt(patternIndex);
            if (token == '*') {
                result = matchesChars(pattern, candidate, patternIndex + 1, candidateIndex, memo)
                        || (candidateIndex < candidate.length()
                        && matchesChars(pattern, candidate, patternIndex, candidateIndex + 1, memo));
            } else {
                result = candidateIndex < candidate.length()
                        && (token == '?' || token == candidate.charAt(candidateIndex))
                        && matchesChars(pattern, candidate, patternIndex + 1, candidateIndex + 1, memo);
            }
        }
        memo[patternIndex][candidateIndex] = result;
        return result;
    }

    private static String normalizeExact(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Target selector must not be blank.");
        if (text.indexOf('*') >= 0 || text.indexOf('?') >= 0) {
            throw new IllegalArgumentException("Wildcard targets require the explicit glob: prefix.");
        }
        return PatchScanner.normalizeAssetPath(text);
    }

    private static String normalizeGlob(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Glob target must not be blank.");
        String normalized = text.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*") || normalized.contains("//")) {
            throw new IllegalArgumentException("Unsafe glob target: glob:" + text);
        }
        String[] segments = normalized.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Unsafe glob target: glob:" + text);
            }
            // A raw wildcard is legal only as one of the supported tokens.  All
            // other syntax is literal, including regular-expression metacharacters.
            for (int index = 0; index < segment.length(); index++) {
                char token = segment.charAt(index);
                if (token != '*' && token != '?') continue;
                if (token == '*' && index + 1 < segment.length() && segment.charAt(index + 1) == '*') {
                    index++;
                }
            }
        }
        return normalized;
    }

    private static String stablePrefix(String body) {
        int wildcard = -1;
        for (int index = 0; index < body.length(); index++) {
            char token = body.charAt(index);
            if (token == '*' || token == '?') {
                wildcard = index;
                break;
            }
        }
        if (wildcard < 0) return body;
        // Preserve the literal prefix exactly, including a trailing slash when
        // the wildcard begins a new path segment.  Future event matching can
        // therefore use startsWith without confusing sibling prefixes.
        return body.substring(0, wildcard);
    }
}
