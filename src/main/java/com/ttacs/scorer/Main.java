package com.ttacs.scorer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ttacs.scorer.api.EvaluateHttpServer;
import com.ttacs.scorer.domain.CreditDecision;
import com.ttacs.scorer.domain.EvaluateRequest;
import com.ttacs.scorer.domain.MpesaTransaction;
import com.ttacs.scorer.domain.ParsedStatement;
import com.ttacs.scorer.ingest.PdfStatementParser;
import com.ttacs.scorer.ingest.StatementUnlockException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class Main {

    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        String command = args[0];
        if ("extract".equals(command)) {
            runExtract(args);
            return;
        }

        Path policyPath = resolvePolicyPath(args);
        AppContext context = AppContext.create(policyPath);

        switch (command) {
            case "evaluate" -> runEvaluate(context, args);
            case "serve" -> new EvaluateHttpServer(context).start();
            case "policy" -> printPolicy(context);
            case "report" -> printReport(context);
            default -> {
                printUsage();
                System.exit(1);
            }
        }

        if ("serve".equals(command)) {
            Thread.currentThread().join();
        }
    }

    private static void runExtract(String[] args) throws Exception {
        String file = null;
        String password = null;
        String outDir = "samples/extracted";
        String msisdn = "";

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--file" -> file = requireValue(args, ++i, "--file");
                case "--password" -> password = requireValue(args, ++i, "--password");
                case "--out" -> outDir = requireValue(args, ++i, "--out");
                case "--msisdn" -> msisdn = requireValue(args, ++i, "--msisdn");
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }

        if (file == null) {
            throw new IllegalArgumentException("--file is required for extract");
        }

        Path pdfPath = Path.of(file);
        byte[] bytes = Files.readAllBytes(pdfPath);
        Path outputDir = Path.of(outDir);
        Files.createDirectories(outputDir);

        String baseName = pdfPath.getFileName().toString().replaceAll("(?i)\\.pdf$", "");
        PdfStatementParser pdfParser = new PdfStatementParser();

        try {
            String rawText = pdfParser.extractText(bytes, password);
            Files.writeString(outputDir.resolve(baseName + "-extracted-text.txt"), rawText, StandardCharsets.UTF_8);

            ParsedStatement parsed = pdfParser.parse(bytes, password, msisdn);
            writeTransactionsCsv(outputDir.resolve(baseName + "-transactions.csv"), parsed);
            Files.writeString(
                    outputDir.resolve(baseName + "-parse-summary.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(buildParseSummary(pdfPath, parsed)),
                    StandardCharsets.UTF_8
            );

            System.out.println("Extraction complete:");
            System.out.println("  text:         " + outputDir.resolve(baseName + "-extracted-text.txt"));
            System.out.println("  transactions: " + outputDir.resolve(baseName + "-transactions.csv"));
            System.out.println("  summary:      " + outputDir.resolve(baseName + "-parse-summary.json"));
            System.out.println("  transactions: " + parsed.transactions().size());
            System.out.println("  warnings:     " + parsed.warnings().size());
        } catch (StatementUnlockException ex) {
            System.err.println(ex.getMessage());
            System.exit(2);
        }
    }

    private static Map<String, Object> buildParseSummary(Path source, ParsedStatement parsed) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sourceFile", source.getFileName().toString());
        summary.put("format", parsed.format().name());
        summary.put("customerName", parsed.customerName());
        summary.put("msisdn", parsed.msisdn());
        summary.put("email", parsed.email());
        summary.put("periodStart", parsed.periodStart() != null ? parsed.periodStart().toString() : null);
        summary.put("periodEnd", parsed.periodEnd() != null ? parsed.periodEnd().toString() : null);
        summary.put("transactionCount", parsed.transactions().size());
        summary.put("warnings", parsed.warnings());
        return summary;
    }

    private static void writeTransactionsCsv(Path path, ParsedStatement parsed) throws Exception {
        StringBuilder csv = new StringBuilder("date,time,direction,amount_kes,counterparty,raw_line\n");
        for (MpesaTransaction txn : parsed.transactions()) {
            csv.append(txn.transactionDate()).append(',');
            csv.append(txn.transactionInstant()).append(',');
            csv.append(txn.direction()).append(',');
            csv.append(txn.amountKes().toPlainString()).append(',');
            csv.append(escapeCsv(txn.counterparty())).append(',');
            csv.append(escapeCsv(txn.rawLine())).append('\n');
        }
        Files.writeString(path, csv.toString(), StandardCharsets.UTF_8);
    }

    private static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private static void runEvaluate(AppContext context, String[] args) throws Exception {
        String file = null;
        String name = "Applicant";
        String msisdn = "";
        String password = null;
        int requested = 0;
        int repayment = 0;
        int activeLoans = 0;
        String appId = "APP-" + UUID.randomUUID().toString().substring(0, 8);

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--file" -> file = requireValue(args, ++i, "--file");
                case "--name" -> name = requireValue(args, ++i, "--name");
                case "--msisdn" -> msisdn = requireValue(args, ++i, "--msisdn");
                case "--password" -> password = requireValue(args, ++i, "--password");
                case "--requested" -> requested = Integer.parseInt(requireValue(args, ++i, "--requested"));
                case "--repayment" -> repayment = Integer.parseInt(requireValue(args, ++i, "--repayment"));
                case "--active-loans" -> activeLoans = Integer.parseInt(requireValue(args, ++i, "--active-loans"));
                case "--app-id" -> appId = requireValue(args, ++i, "--app-id");
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }

        if (file == null) {
            throw new IllegalArgumentException("--file is required for evaluate");
        }

        Path statementPath = Path.of(file);
        byte[] bytes = Files.readAllBytes(statementPath);
        EvaluateRequest request = EvaluateRequest.fromFile(
                appId, name, msisdn, requested, repayment, activeLoans, bytes, password
        );

        try {
            CreditDecision decision = context.scoringService().evaluate(request);
            System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(decision));
        } catch (StatementUnlockException ex) {
            System.err.println(ex.getMessage());
            System.exit(2);
        }
    }

    private static void printPolicy(AppContext context) throws Exception {
        var materialized = context.materializedPolicy();
        System.out.println("Policy source: " + materialized.source());
        System.out.println("Snippet: " + materialized.snippet());
        System.out.println("Policy version: " + com.ttacs.scorer.policy.PolicyMaterializer.policyVersion(materialized));
        if (materialized.pdfPath() != null) {
            System.out.println("Policy PDF: " + materialized.pdfPath());
        }
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(materialized.policy()));
    }

    private static void printReport(AppContext context) throws Exception {
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                context.decisionStore().portfolioReport()
        ));
    }

    private static Path resolvePolicyPath(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--policy".equals(args[i])) {
                return Path.of(args[i + 1]);
            }
        }
        return Path.of("policy.yml");
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + flag);
        }
        return args[index];
    }

    private static void printUsage() {
        System.out.println("""
                M-Pesa Credit Scorer

                Usage:
                  java -jar mpesa-credit-scorer.jar evaluate --file <statement.csv|pdf> [options]
                  java -jar mpesa-credit-scorer.jar extract --file <statement.pdf> --password <pwd> [--out dir]
                  java -jar mpesa-credit-scorer.jar serve
                  java -jar mpesa-credit-scorer.jar policy
                  java -jar mpesa-credit-scorer.jar report

                Options:
                  --policy <path>         Policy YAML (default: policy.yml)
                  --file <path>           M-Pesa statement CSV or PDF
                  --password <value>      PDF unlock password
                  --name <applicant>      Applicant name
                  --msisdn <number>       Phone hint
                  --requested <kes>       Optional requested loan amount
                  --repayment <kes>       Optional declared monthly repayment
                  --active-loans <n>      Declared active loans
                  --app-id <id>           Application id
                """);
    }
}
