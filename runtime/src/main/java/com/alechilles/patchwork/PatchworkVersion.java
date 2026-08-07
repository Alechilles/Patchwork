package com.alechilles.patchwork;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Resolves the Patchwork runtime version used for logical telemetry identity. */
public final class PatchworkVersion {
    private static final String FALLBACK = "1.3.0";

    private PatchworkVersion() {
    }

    public static String current() {
        String configured = valid(System.getProperty("patchwork.version"));
        String packaged = valid(PatchworkVersion.class.getPackage().getImplementationVersion());
        String implementation = configured != null ? configured : packaged != null ? packaged : readMavenVersion();
        return implementation == null ? FALLBACK : implementation;
    }

    private static String readMavenVersion() {
        try (InputStream stream = PatchworkVersion.class.getClassLoader()
                .getResourceAsStream("META-INF/maven/com.alechilles/patchwork-runtime/pom.properties")) {
            if (stream == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(stream);
            return valid(properties.getProperty("version"));
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String valid(String value) {
        return value != null && value.matches("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")
                ? value
                : null;
    }
}
