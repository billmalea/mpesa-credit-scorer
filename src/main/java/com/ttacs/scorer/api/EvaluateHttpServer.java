package com.ttacs.scorer.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ttacs.scorer.AppContext;
import com.ttacs.scorer.CreditScoringService;
import com.ttacs.scorer.domain.CreditDecision;
import com.ttacs.scorer.domain.EvaluateRequest;
import com.ttacs.scorer.domain.StatementPreview;
import com.ttacs.scorer.ingest.StatementUnlockException;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

public final class EvaluateHttpServer {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final CreditScoringService scoringService;
    private final com.ttacs.scorer.flexvertex.DecisionStore decisionStore;
    private final int port;

    public EvaluateHttpServer(AppContext context) {
        this.scoringService = context.scoringService();
        this.decisionStore = context.decisionStore();
        this.port = context.serverPort();
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", exchange -> {
            DemoSecurity.applySecurityHeaders(exchange);
            if (!DemoSecurity.authorize(exchange)) {
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("/api/v1/evaluate".equals(path)) {
                handleEvaluate(exchange);
                return;
            }
            if ("/api/v1/parse".equals(path)) {
                handleParse(exchange);
                return;
            }
            if ("/api/v1/report".equals(path) && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 200, decisionStore.portfolioReport());
                return;
            }
            if (path.startsWith("/api/v1/applications/") && path.endsWith("/reconstruct")) {
                handleReconstruct(exchange, path);
                return;
            }
            writeJson(exchange, 404, Map.of("error", "Not found"));
        });
        // Health stays public so tunnels/monitors can probe without credentials.
        server.createContext("/health", exchange -> {
            DemoSecurity.applySecurityHeaders(exchange);
            writeJson(exchange, 200, Map.of("status", "ok"));
        });
        server.createContext("/", new StaticResourceHandler());
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("M-Pesa credit scorer UI:  http://localhost:" + port + "/");
        System.out.println("Parse API:               POST http://localhost:" + port + "/api/v1/parse");
        System.out.println("Evaluate API:            POST http://localhost:" + port + "/api/v1/evaluate");
        System.out.println("Reconstruct API:         GET  http://localhost:" + port + "/api/v1/applications/{id}/reconstruct");
        System.out.println("Portfolio report:        GET  http://localhost:" + port + "/api/v1/report");
        if (DemoSecurity.authEnabled()) {
            System.out.println("Demo Basic Auth:         enabled (SCORER_BASIC_AUTH)");
        } else {
            System.out.println("Demo Basic Auth:         disabled — set SCORER_BASIC_AUTH=user:pass before public tunnels");
        }
    }

    private void handleReconstruct(HttpExchange exchange, String path) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }

        String prefix = "/api/v1/applications/";
        String suffix = "/reconstruct";
        if (!path.startsWith(prefix) || !path.endsWith(suffix) || path.length() <= prefix.length() + suffix.length()) {
            writeJson(exchange, 404, Map.of("error", "Not found"));
            return;
        }

        String applicationId = path.substring(prefix.length(), path.length() - suffix.length());
        var result = scoringService.reconstruct(applicationId);
        if (result.isEmpty()) {
            writeJson(exchange, 404, Map.of("error", "Application not found in audit graph"));
            return;
        }
        writeJson(exchange, 200, result.get());
    }

    private void handleParse(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        try {
            MultipartFormData form = parseMultipart(exchange);
            byte[] fileBytes = requireStatement(form);
            EvaluateRequest request = EvaluateRequest.fromFile(
                    "PREVIEW",
                    "",
                    "",
                    0,
                    0,
                    0,
                    fileBytes,
                    form.field("statementPassword", null)
            );
            StatementPreview preview = scoringService.preview(request);
            writeJson(exchange, 200, preview);
        } catch (StatementUnlockException ex) {
            writeJson(exchange, 422, Map.of("error", DemoSecurity.publicError(ex)));
        } catch (IllegalArgumentException ex) {
            writeJson(exchange, 400, Map.of("error", DemoSecurity.publicError(ex)));
        } catch (Exception ex) {
            writeJson(exchange, 500, Map.of("error", DemoSecurity.publicError(ex)));
        }
    }

    private void handleEvaluate(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }

        try {
            MultipartFormData form = parseMultipart(exchange);
            byte[] fileBytes = requireStatement(form);

            EvaluateRequest request = EvaluateRequest.fromFile(
                    form.field("applicationId", "APP-" + UUID.randomUUID().toString().substring(0, 8)),
                    form.field("applicantName", ""),
                    form.field("msisdn", ""),
                    form.intField("requestedAmountKes", 0),
                    form.intField("projectedMonthlyRepaymentKes", 0),
                    form.intField("activeLoanCount", 0),
                    fileBytes,
                    form.field("statementPassword", null)
            );

            CreditDecision decision = scoringService.evaluate(request);
            writeJson(exchange, 200, decision);
        } catch (StatementUnlockException ex) {
            writeJson(exchange, 422, Map.of("error", DemoSecurity.publicError(ex)));
        } catch (IllegalArgumentException ex) {
            writeJson(exchange, 400, Map.of("error", DemoSecurity.publicError(ex)));
        } catch (Exception ex) {
            writeJson(exchange, 500, Map.of("error", DemoSecurity.publicError(ex)));
        }
    }

    private MultipartFormData parseMultipart(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("multipart/form-data")) {
            throw new IllegalArgumentException("Expected multipart/form-data with statement file");
        }
        return MultipartFormData.parse(contentType, DemoSecurity.readBodyCapped(exchange));
    }

    private byte[] requireStatement(MultipartFormData form) {
        byte[] fileBytes = form.fileBytes("statement");
        if (fileBytes == null || fileBytes.length == 0) {
            throw new IllegalArgumentException("Missing statement file part");
        }
        return fileBytes;
    }

    private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Minimal multipart parser for one file + text fields. */
    static final class MultipartFormData {
        private final Map<String, String> fields = new HashMap<>();
        private byte[] fileBytes;

        static MultipartFormData parse(String contentType, byte[] body) {
            String boundaryToken = contentType.substring(contentType.indexOf("boundary=") + 9).trim();
            String boundary = "--" + boundaryToken;
            MultipartFormData form = new MultipartFormData();
            String payload = new String(body, StandardCharsets.ISO_8859_1);
            String[] parts = payload.split(boundary);

            for (String part : parts) {
                if (!part.contains("Content-Disposition")) {
                    continue;
                }
                int headerEnd = part.indexOf("\r\n\r\n");
                if (headerEnd < 0) {
                    continue;
                }
                String headers = part.substring(0, headerEnd);
                String rawContent = part.substring(headerEnd + 4);
                if (rawContent.endsWith("\r\n")) {
                    rawContent = rawContent.substring(0, rawContent.length() - 2);
                }

                String name = extractName(headers);
                if (name == null) {
                    continue;
                }
                if (headers.contains("filename=")) {
                    form.fileBytes = rawContent.getBytes(StandardCharsets.ISO_8859_1);
                } else {
                    form.fields.put(name, rawContent.trim());
                }
            }
            return form;
        }

        private static String extractName(String headers) {
            for (String line : headers.split("\r\n")) {
                if (line.startsWith("Content-Disposition")) {
                    int idx = line.indexOf("name=\"");
                    if (idx >= 0) {
                        int end = line.indexOf('"', idx + 6);
                        return line.substring(idx + 6, end);
                    }
                }
            }
            return null;
        }

        String field(String name, String defaultValue) {
            return fields.getOrDefault(name, defaultValue);
        }

        int intField(String name, int defaultValue) {
            String value = fields.get(name);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            return Integer.parseInt(value);
        }

        byte[] fileBytes(String name) {
            if ("statement".equals(name)) {
                return fileBytes;
            }
            return null;
        }
    }
}
