package com.ttacs.scorer.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class StaticResourceHandler implements HttpHandler {

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "application/javascript; charset=utf-8",
            "csv", "text/csv; charset=utf-8",
            "svg", "image/svg+xml",
            "ico", "image/x-icon",
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg"
    );

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        DemoSecurity.applySecurityHeaders(exchange);

        if (!DemoSecurity.isSafeMethod(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
                && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        if (!DemoSecurity.authorize(exchange)) {
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            path = "/index.html";
        }

        path = URLDecoder.decode(path, StandardCharsets.UTF_8);
        if (!DemoSecurity.isAllowedStaticPath(path)) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }

        String resourcePath = "/static" + path;
        if (resourcePath.contains("..") || resourcePath.contains("\\")) {
            exchange.sendResponseHeaders(403, -1);
            return;
        }

        try (InputStream in = StaticResourceHandler.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            byte[] bytes = in.readAllBytes();
            String extension = extension(resourcePath);
            String contentType = CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");

            exchange.getResponseHeaders().set("Content-Type", contentType);
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private String extension(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return "";
        }
        return path.substring(dot + 1).toLowerCase();
    }
}
