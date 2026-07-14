package com.ttacs.scorer.api;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * Demo-facing HTTP guards: optional Basic Auth, upload caps, and safe static paths.
 * Credentials come from {@code SCORER_BASIC_AUTH=user:password} (never from policy.yml).
 */
final class DemoSecurity {

    static final int MAX_UPLOAD_BYTES = 8 * 1024 * 1024;

    private static final Set<String> ALLOWED_STATIC = Set.of(
            "/index.html",
            "/app.js",
            "/styles.css",
            "/favicon.ico"
    );

    private DemoSecurity() {
    }

    static String basicAuthCredential() {
        String value = System.getenv("SCORER_BASIC_AUTH");
        if (value == null || value.isBlank()) {
            return null;
        }
        int colon = value.indexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            throw new IllegalStateException("SCORER_BASIC_AUTH must be user:password");
        }
        return value;
    }

    static boolean authEnabled() {
        return basicAuthCredential() != null;
    }

    /** Returns true if the request may proceed. Sends 401 when auth is required and missing. */
    static boolean authorize(HttpExchange exchange) throws IOException {
        String expected = basicAuthCredential();
        if (expected == null) {
            return true;
        }

        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.regionMatches(true, 0, "Basic ", 0, 6)) {
            String decoded = new String(
                    Base64.getDecoder().decode(header.substring(6).trim()),
                    StandardCharsets.UTF_8
            );
            if (constantTimeEquals(expected, decoded)) {
                return true;
            }
        }

        Headers headers = exchange.getResponseHeaders();
        headers.set("WWW-Authenticate", "Basic realm=\"TTACS Credit Scorer Demo\"");
        headers.set("Cache-Control", "no-store");
        byte[] body = "{\"error\":\"Unauthorized\"}".getBytes(StandardCharsets.UTF_8);
        headers.set("Content-Type", "application/json");
        exchange.sendResponseHeaders(401, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
        return false;
    }

    static void applySecurityHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cache-Control", "no-store");
        headers.set("Content-Security-Policy",
                "default-src 'self'; img-src 'self' data:; style-src 'self' https://fonts.googleapis.com; "
                        + "font-src 'self' https://fonts.gstatic.com; script-src 'self'; connect-src 'self'; "
                        + "base-uri 'self'; form-action 'self'");
    }

    static boolean isAllowedStaticPath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return true;
        }
        if (ALLOWED_STATIC.contains(path)) {
            return true;
        }
        // Synthetic demo CSVs only — never real statement extracts.
        return path.startsWith("/samples/")
                && path.endsWith(".csv")
                && !path.contains("..")
                && path.indexOf('/', 1) == path.lastIndexOf('/');
    }

    static byte[] readBodyCapped(HttpExchange exchange) throws IOException {
        String lengthHeader = exchange.getRequestHeaders().getFirst("Content-Length");
        if (lengthHeader != null) {
            try {
                long length = Long.parseLong(lengthHeader.trim());
                if (length > MAX_UPLOAD_BYTES) {
                    throw new IllegalArgumentException("Upload exceeds " + MAX_UPLOAD_BYTES + " byte limit");
                }
            } catch (NumberFormatException ignored) {
                // fall through to stream cap
            }
        }

        byte[] all = exchange.getRequestBody().readNBytes(MAX_UPLOAD_BYTES + 1);
        if (all.length > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("Upload exceeds " + MAX_UPLOAD_BYTES + " byte limit");
        }
        return all;
    }

    static String publicError(Exception ex) {
        if (ex instanceof IllegalArgumentException || ex instanceof com.ttacs.scorer.ingest.StatementUnlockException) {
            return ex.getMessage();
        }
        System.err.println("Request failed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        return "Internal error";
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] left = a.getBytes(StandardCharsets.UTF_8);
        byte[] right = b.getBytes(StandardCharsets.UTF_8);
        if (left.length != right.length) {
            // still compare to reduce timing signal on length
            int result = left.length ^ right.length;
            int limit = Math.min(left.length, right.length);
            for (int i = 0; i < limit; i++) {
                result |= left[i] ^ right[i];
            }
            return false;
        }
        int result = 0;
        for (int i = 0; i < left.length; i++) {
            result |= left[i] ^ right[i];
        }
        return result == 0;
    }

    static boolean isHealthPath(String path) {
        return "/health".equals(path);
    }

    static boolean isSafeMethod(String method) {
        if (method == null) {
            return false;
        }
        String upper = method.toUpperCase(Locale.ROOT);
        return "GET".equals(upper) || "POST".equals(upper) || "HEAD".equals(upper) || "OPTIONS".equals(upper);
    }
}
